import gitlab
import os
from dotenv import load_dotenv

class BaseGitLabClient:
    """Base GitLab client class that provides common functionality"""
    
    def __init__(self):
        # Load environment variables
        load_dotenv()
        
        # Initialize GitLab connection
        self.gitlab_url = os.getenv('GITLAB_URL', 'https://gitlab.com')
        self.gitlab_token = os.getenv('GITLAB_TOKEN')
        
        # Initialize GitLab client but don't connect yet
        self.gl = gitlab.Gitlab(
            self.gitlab_url,
            private_token=self.gitlab_token,
            ssl_verify=False
        )
    
    def _ensure_connection(self):
        """Ensure GitLab connection is properly initialized"""
        if not self.gitlab_token:
            raise ValueError("GITLAB_TOKEN environment variable is required")
        
        # Test connection
        try:
            self.gl.auth()
        except Exception as e:
            raise ConnectionError(f"Failed to connect to GitLab: {str(e)}")
    
    def validate_connection(self):
        """Validate the GitLab connection"""
        self._ensure_connection()
        try:
            # Try to get the current user to validate connection
            self.gl.user
            return True
        except Exception as e:
            raise ConnectionError(f"Failed to connect to GitLab: {str(e)}")
    
    def get_project(self, project_id):
        """Get a project by ID or path"""
        self._ensure_connection()
        return self.gl.projects.get(project_id)
    
    def get_user(self, user_id):
        """Get a user by ID or username"""
        self._ensure_connection()
        return self.gl.users.get(user_id)
    
    def get_group(self, group_id):
        """Get a group by ID or path"""
        self._ensure_connection()
        return self.gl.groups.get(group_id)