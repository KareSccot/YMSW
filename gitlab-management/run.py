#!/usr/bin/env python3
"""
启动脚本 for GitLab Management API
"""

import os
import sys
from dotenv import load_dotenv

# Load environment variables
load_dotenv()

# Set default values
HOST = os.getenv("API_HOST", "0.0.0.0")
PORT = int(os.getenv("API_PORT", "8000"))
RELOAD = os.getenv("API_RELOAD", "false").lower() == "true"

# Add the current directory to the path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

# Import the FastAPI app
from api.main import app

if __name__ == "__main__":
    import uvicorn
    
    print(f"Starting GitLab Management API on {HOST}:{PORT}")
    print(f"API Documentation: http://{HOST}:{PORT}/docs")
    
    # Run the application
    uvicorn.run(
        "api.main:app",
        host=HOST,
        port=PORT,
        reload=RELOAD
    )