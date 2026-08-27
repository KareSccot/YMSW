# API Routes

from .projects import router as projects_router
from .users import router as users_router
from .pipelines import router as pipelines_router
from .files import router as files_router
from .argocd import router as argocd_router

__all__ = ["projects", "users", "pipelines", "files", "argocd"]

# Import aliases for easier import from .routes
projects = projects_router
users = users_router
pipelines = pipelines_router
files = files_router
argocd = argocd_router