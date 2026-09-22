from lib.gitlab_client import BaseGitLabClient

class ProjectService(BaseGitLabClient):
    def __init__(self):
        super().__init__()
    
    def create_project(self, name, namespace_id=None):
        """Create a new GitLab project"""
        self._ensure_connection()
        project_data = {
            'name': name,
            'visibility': 'private'
        }
        if namespace_id:
            project_data['namespace_id'] = namespace_id
        
        return self.gl.projects.create(project_data)
    
    def list_projects(self, namespace_id=None):
        """List projects"""
        self._ensure_connection()
        if namespace_id:
            return self.gl.projects.list(namespace_id=namespace_id)
        return self.gl.projects.list()