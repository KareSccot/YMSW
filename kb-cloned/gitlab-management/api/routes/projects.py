from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from services.project_management.project_service import ProjectService

router = APIRouter()
project_service = ProjectService()

# Request models
class CreateProjectRequest(BaseModel):
    name: str
    namespace_id: int | None = None

class ProjectListRequest(BaseModel):
    namespace_id: int | None = None

# Response models
class ProjectResponse(BaseModel):
    id: int
    name: str
    path: str
    web_url: str
    visibility: str

@router.post("/", response_model=ProjectResponse)
async def create_project(request: CreateProjectRequest):
    """Create a new GitLab project"""
    try:
        project = project_service.create_project(request.name, request.namespace_id)
        return ProjectResponse(
            id=project.id,
            name=project.name,
            path=project.path,
            web_url=project.web_url,
            visibility=project.visibility
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/{project_id}", response_model=ProjectResponse)
async def get_project(project_id: int | str):
    """Get project details by ID or path"""
    try:
        project = project_service.get_project(project_id)
        return ProjectResponse(
            id=project.id,
            name=project.name,
            path=project.path,
            web_url=project.web_url,
            visibility=project.visibility
        )
    except Exception as e:
        raise HTTPException(status_code=404, detail=str(e))

@router.get("/", response_model=list[ProjectResponse])
async def list_projects(namespace_id: int | None = None):
    """List all projects"""
    try:
        projects = project_service.list_projects(namespace_id)
        return [
            ProjectResponse(
                id=project.id,
                name=project.name,
                path=project.path,
                web_url=project.web_url,
                visibility=project.visibility
            ) for project in projects
        ]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))