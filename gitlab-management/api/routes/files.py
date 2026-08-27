from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import List, Dict
from services.file_management.file_service import FileService

router = APIRouter()
file_service = FileService()

# Request models
class GetFileRequest(BaseModel):
    file_path: str
    ref: str = "main"

class UpdateFileRequest(BaseModel):
    file_path: str
    content: str
    branch: str = "main"
    commit_message: str = "Update file"

class CreateFileRequest(BaseModel):
    file_path: str
    content: str
    branch: str = "main"
    commit_message: str = "Create file"

class DeleteFileRequest(BaseModel):
    file_path: str
    branch: str = "main"
    commit_message: str = "Delete file"

# Response models
class FileResponse(BaseModel):
    file_name: str
    file_path: str
    content: str
    last_commit_id: str

class FileOperationResponse(BaseModel):
    file_name: str | None
    file_path: str
    last_commit_id: str | None
    message: str

class FileListResponse(BaseModel):
    name: str
    path: str
    type: str
    mode: str

@router.post("/{project_id}/get", response_model=FileResponse)
async def get_file(project_id: int | str, request: GetFileRequest):
    """Get file content from a project"""
    try:
        file_content = file_service.get_file(project_id, request.file_path, request.ref)
        return FileResponse(**file_content)
    except Exception as e:
        raise HTTPException(status_code=404, detail=str(e))

@router.post("/{project_id}/update", response_model=FileOperationResponse)
async def update_file(project_id: int | str, request: UpdateFileRequest):
    """Update an existing file"""
    try:
        result = file_service.update_file(
            project_id,
            request.file_path,
            request.content,
            request.branch,
            request.commit_message
        )
        return FileOperationResponse(**result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/{project_id}/create", response_model=FileOperationResponse)
async def create_file(project_id: int | str, request: CreateFileRequest):
    """Create a new file"""
    try:
        result = file_service.create_file(
            project_id,
            request.file_path,
            request.content,
            request.branch,
            request.commit_message
        )
        return FileOperationResponse(**result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/{project_id}/delete", response_model=FileOperationResponse)
async def delete_file(project_id: int | str, request: DeleteFileRequest):
    """Delete a file"""
    try:
        result = file_service.delete_file(
            project_id,
            request.file_path,
            request.branch,
            request.commit_message
        )
        return FileOperationResponse(**result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

class ListFilesRequest(BaseModel):
    path: str = ""
    ref: str = "main"

@router.post("/{project_id}/list", response_model=List[FileListResponse])
async def list_files(project_id: int | str, request: ListFilesRequest):
    """List files in a directory"""
    try:
        files = file_service.list_files(project_id, request.path, request.ref)
        return [FileListResponse(**file) for file in files]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))