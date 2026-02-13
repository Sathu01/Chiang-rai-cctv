import firebase_admin
from firebase_admin import credentials, firestore
from typing import Dict
import os

class FirestoreRepository:
    def __init__(self, credentials_path: str = None):
        # Use credentials from environment or default path
        cred_path = credentials_path or os.getenv('FIREBASE_CREDENTIALS_PATH', 'secrets/serviceAccount.json')
        if not firebase_admin._apps:
            cred = credentials.Certificate(cred_path)
            firebase_admin.initialize_app(cred)
        self.db = firestore.client()

    def save_license_plate(self, data: Dict):
        """
        Save detection result to 'licensePlates' collection.
        """
        self.db.collection('licensePlates').add(data)
