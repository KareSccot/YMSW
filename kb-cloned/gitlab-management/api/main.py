from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

# Create FastAPI app
app = FastAPI(
    title="GitLab Management API",
    description="API for managing GitLab projects, users, pipelines, and files",
    version="1.0.0"
)

# Configure CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # In production, replace with specific origins
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Health check endpoint
@app.get("/health")
async def health_check():
    return {"status": "healthy"}

# Import and register routes
from .routes import projects, users, pipelines, files, argocd

# Register routers
app.include_router(projects, prefix="/api/projects", tags=["Projects"])
app.include_router(users, prefix="/api/users", tags=["Users"])
app.include_router(pipelines, prefix="/api/pipelines", tags=["Pipelines"])
app.include_router(files, prefix="/api/files", tags=["Files"])
app.include_router(argocd, prefix="/api/argocd", tags=["ArgoCD"])