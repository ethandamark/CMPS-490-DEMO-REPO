package com.CMPS490.weathertracker.ml

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

data class PredictionResult(
    val stormProbability: Float,
    val alertState: Int,
    val threshold: Float,
    val modelVersion: String,
)

private data class IsotonicEntry(val raw: Float, val cal: Float)

private data class ModelMetadata(
    val experiment_name: String,
    val feature_cols: List<String>,
    val threshold: Double,
    val isotonic_table: List<Map<String, Double>>?,
    val imputer_fill_values: Map<String, Double>?,
)

class OnDevicePredictor private constructor(context: Context) {

    companion object {
        private const val TAG = "OnDevicePredictor"
        private const val MODEL_FILE = "ml/model.onnx"
        private const val METADATA_FILE = "ml/model_metadata.json"

        @Volatile
        private var instance: OnDevicePredictor? = null

        fun getInstance(context: Context): OnDevicePredictor =
            instance ?: synchronized(this) {
                instance ?: OnDevicePredictor(context.applicationContext).also { instance = it }
            }
    }

    private val metadata: ModelMetadata
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    private val isotonicTable: List<IsotonicEntry>?
    private val imputerFills: Map<String, Float>

    init {
        val metaJson = context.assets.open(METADATA_FILE).bufferedReader().use { it.readText() }
        metadata = Gson().fromJson(metaJson, ModelMetadata::class.java)

        isotonicTable = metadata.isotonic_table?.map { entry ->
            IsotonicEntry(
                raw = entry["raw"]?.toFloat() ?: 0f,
                cal = entry["cal"]?.toFloat() ?: 0f,
            )
        }

        // Load imputer fill values (median) for missing feature imputation
        imputerFills = metadata.imputer_fill_values
            ?.mapValues { it.value.toFloat() }
            ?: emptyMap()

        // Load ONNX session — model.onnx may not exist at build time (only after conversion)
        try {
            val modelBytes = context.assets.open(MODEL_FILE).readBytes()
            ortSession = ortEnv.createSession(modelBytes, OrtSession.SessionOptions())
            Log.i(TAG, "ONNX session loaded; features=${metadata.feature_cols.size}")
        } catch (e: Exception) {
            Log.w(TAG, "ONNX model not available: ${e.message}. Predictions will return 0.")
        }
    }

    fun predict(features: Map<String, Float?>): PredictionResult {
        val session = ortSession
            ?: return PredictionResult(0f, 0, metadata.threshold.toFloat(), metadata.experiment_name)

        // Apply imputation: use the feature value if present, otherwise
        // fall back to the median fill value from the training set.
        val featureValues = metadata.feature_cols.map { col ->
            val v = features[col]
            when {
                v != null && !v.isNaN() -> v
                else -> imputerFills[col] ?: 0f
            }
        }

        val floatBuffer = FloatBuffer.wrap(featureValues.toFloatArray())
        val shape = longArrayOf(1, featureValues.size.toLong())
        val inputName = session.inputNames.first()
        val tensor = OnnxTensor.createTensor(ortEnv, floatBuffer, shape)

        return tensor.use {
            val results = session.run(mapOf(inputName to it))
            results.use { output ->
                // Probability is in output[1] (predict_proba), index 1 = positive class
                @Suppress("UNCHECKED_CAST")
                val probArray = (output[1].value as Array<FloatArray>)[0]
                val rawProb = probArray[1]
                val calibratedProb = calibrate(rawProb)
                val threshold = metadata.threshold.toFloat()
                PredictionResult(
                    stormProbability = calibratedProb,
                    alertState = if (calibratedProb >= threshold) 1 else 0,
                    threshold = threshold,
                    modelVersion = metadata.experiment_name,
                )
            }
        }
    }

    private fun calibrate(rawProb: Float): Float {
        val table = isotonicTable ?: return rawProb
        if (table.isEmpty()) return rawProb
        if (rawProb <= table.first().raw) return table.first().cal
        if (rawProb >= table.last().raw) return table.last().cal

        val idx = table.indexOfFirst { it.raw >= rawProb }
        if (idx <= 0) return table.first().cal
        val lo = table[idx - 1]
        val hi = table[idx]
        val t = (rawProb - lo.raw) / (hi.raw - lo.raw)
        return lo.cal + t * (hi.cal - lo.cal)
    }
}
