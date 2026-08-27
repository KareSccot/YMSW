from lib.gitlab_client import BaseGitLabClient

class UserService(BaseGitLabClient):
    def __init__(self):
        super().__init__()
    
    def create_user(self, email, username, name, password):
        """Create a new GitLab user"""
        self._ensure_connection()
        user_data = {
            'email': email,
            'username': username,
            'name': name,
            'password': password,
            'skip_confirmation': True
        }
        return self.gl.users.create(user_data)
    
    def list_users(self, active=True):
        """List users"""
        self._ensure_connection()
        return self.gl.users.list(active=active)