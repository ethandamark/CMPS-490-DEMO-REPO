Test Commands:
    First make sure you are in the backend folder directory. (i.e. .../CMPS-490-DEMO-REP0/backend/)
    Hardcoded Weather Prediction:
        python -m pytest tests/test_prediction_model.py -v -s
    Area:
        python -m pytest tests/test_area_predictions.py -v
    Live Integration (Supabase + real weather):
        python -m pytest tests/test_live_integration.py -v -s