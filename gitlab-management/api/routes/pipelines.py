from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Dict, Any
from services.ci_cd_management.pipeline_service import PipelineService

router = APIRouter()
pipeline_service = PipelineService()

# Request models
class RunPipelineRequest(BaseModel):
    project_id: int | str
    ref: str = "main"
    variables: Dict[str, Any] | None = None

class PipelineListRequest(BaseModel):
    project_id: int | str
    status: str | None = None

# Response models
class PipelineResponse(BaseModel):
    id: int
    project_id: int
    status: str
    ref: str
    sha: str
    web_url: str

@router.post("/", response_model=PipelineResponse)
async def run_pipeline(request: RunPipelineRequest):
    """Run a pipeline for a project"""
    try:
        pipeline = pipeline_service.run_pipeline(
            request.project_id,
            request.ref,
            request.variables
        )
        return PipelineResponse(
            id=pipeline.id,
            project_id=pipeline.project_id,
            status=pipeline.status,
            ref=pipeline.ref,
            sha=pipeline.sha,
            web_url=pipeline.web_url
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/{project_id}/{pipeline_id}", response_model=PipelineResponse)
async def get_pipeline(project_id: int | str, pipeline_id: int):
    """Get pipeline details by ID"""
    try:
        pipeline = pipeline_service.get_pipeline(project_id, pipeline_id)
        return PipelineResponse(
            id=pipeline.id,
            project_id=pipeline.project_id,
            status=pipeline.status,
            ref=pipeline.ref,
            sha=pipeline.sha,
            web_url=pipeline.web_url
        )
    except Exception as e:
        raise HTTPException(status_code=404, detail=str(e))

@router.get("/{project_id}/", response_model=list[PipelineResponse])
async def list_pipelines(project_id: int | str, status: str | None = None):
    """List pipelines for a project"""
    try:
        pipelines = pipeline_service.list_pipelines(project_id, status)
        return [
            PipelineResponse(
                id=pipeline.id,
                project_id=pipeline.project_id,
                status=pipeline.status,
                ref=pipeline.ref,
                sha=pipeline.sha,
                web_url=pipeline.web_url
            ) for pipeline in pipelines
        ]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))