package com.CMPS490.weathertracker.ml

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

data class PredictionResult(
    val stormProbability: Float,
    val alertState: Int,      // 0 = clear, 1 = light, 2 = moderate, 3 = severe
    val threshold: Float,
    val modelVersion: String,
    val predictedDbz: Float,  // raw predicted dBZ (before ÷75 normalisation)
)

private data class ModelMetadata(
    val experiment_name: String,
    val feature_cols: List<String>,
    val threshold: Double,
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

        // dBZ tier thresholds (normalised to [0,1] by ÷75)
        const val TIER_LIGHT    = 20f / 75f   // 0.267 → light rain
        const val TIER_MODERATE = 30f / 75f   // 0.400 → moderate rain
        const val TIER_SEVERE   = 40f / 75f   // 0.533 → severe / thunderstorms

        fun probabilityToTier(prob: Float): Int = when {
            prob >= TIER_SEVERE   -> 3
            prob >= TIER_MODERATE -> 2
            prob >= TIER_LIGHT    -> 1
            else                  -> 0
        }

        fun tierToName(tier: Int): String = when (tier) {
            1    -> "light"
            2    -> "moderate"
            3    -> "severe"
            else -> "clear"
        }
    }

    private val metadata: ModelMetadata
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    private val imputerFills: Map<String, Float>

    init {
        val metaJson = context.assets.open(METADATA_FILE).bufferedReader().use { it.readText() }
        metadata = Gson().fromJson(metaJson, ModelMetadata::class.java)

        imputerFills = metadata.imputer_fill_values
            ?.mapValues { it.value.toFloat() }
            ?: emptyMap()

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
            ?: return PredictionResult(0f, 0, metadata.threshold.toFloat(), metadata.experiment_name, 0f)

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
                val threshold = metadata.threshold.toFloat()
                // Regressor: output[0] is predicted dBZ.
                // Normalised to [0,1] by ÷75 so stormProbability >= threshold ↔ dBZ >= dBZ_threshold.
                val rawVal = output[0].value
                val predictedDbz: Float = when (rawVal) {
                    is FloatArray -> rawVal[0]
                    is Array<*> -> @Suppress("UNCHECKED_CAST") (rawVal as Array<FloatArray>)[0][0]
                    else -> rawVal.toString().toFloatOrNull() ?: 0f
                }
                val stormProb = (predictedDbz / 75f).coerceIn(0f, 1f)
                PredictionResult(
                    stormProbability = stormProb,
                    alertState = probabilityToTier(stormProb),
                    threshold = threshold,
                    modelVersion = metadata.experiment_name,
                    predictedDbz = predictedDbz.coerceAtLeast(0f),
                )
            }
        }
    }
}

