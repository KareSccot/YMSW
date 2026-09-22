from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import List, Dict
from services.argocd_deployment.version_service import VersionService

router = APIRouter()
version_service = VersionService()

# Request models
class UpdateVersionRequest(BaseModel):
    file_path: str
    new_version: str
    branch: str = "master"
    commit_message: str = "Update image version"

class BatchUpdateRequest(BaseModel):
    updates: List[Dict[str, str]]
    branch: str = "master"
    commit_message: str = "Batch update image versions"

# Response models
class UpdateVersionResponse(BaseModel):
    file_path: str
    old_version: str
    new_version: str
    last_commit_id: str
    message: str

class BatchUpdateResponse(BaseModel):
    total: int
    successful: int
    failed: int
    results: List[Dict]

@router.post("/{project_id}/update-version", response_model=UpdateVersionResponse)
async def update_image_version(project_id: int | str, request: UpdateVersionRequest):
    """Update image tag in a single deployment file"""
    try:
        result = version_service.update_image_version(
            project_id,
            request.file_path,
            request.new_version,
            request.branch,
            request.commit_message
        )
        return UpdateVersionResponse(**result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/{project_id}/batch-update", response_model=BatchUpdateResponse)
async def batch_update_versions(project_id: int | str, request: BatchUpdateRequest):
    """Batch update image tags across multiple deployment files"""
    try:
        result = version_service.batch_update_versions(
            project_id,
            request.updates,
            request.branch,
            request.commit_message
        )
        return BatchUpdateResponse(**result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
