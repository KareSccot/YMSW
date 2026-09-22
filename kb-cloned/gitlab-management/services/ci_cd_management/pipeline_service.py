from lib.gitlab_client import BaseGitLabClient

class PipelineService(BaseGitLabClient):
    def __init__(self):
        super().__init__()
    
    def run_pipeline(self, project_id, ref='main', variables=None):
        """Run a pipeline for a project"""
        project = self.get_project(project_id)
        pipeline_data = {'ref': ref}
        if variables:
            pipeline_data['variables'] = variables
        return project.pipelines.create(pipeline_data)
    
    def get_pipeline(self, project_id, pipeline_id):
        """Get a pipeline by ID"""
        project = self.get_project(project_id)
        return project.pipelines.get(pipeline_id)
    
    def list_pipelines(self, project_id, status=None):
        """List pipelines for a project"""
        project = self.get_project(project_id)
        if status:
            return project.pipelines.list(status=status)
        return project.pipelines.list()