import gitlab
import os
from dotenv import load_dotenv

# Load environment variables
load_dotenv()

# Initialize GitLab client
gl = gitlab.Gitlab(
    os.getenv('GITLAB_URL'),
    private_token=os.getenv('GITLAB_TOKEN'),
    ssl_verify=False
)

try:
    # Authenticate
    gl.auth()
    print('GitLab connection successful')
    
    # Try to get the project
    project = gl.projects.get(1053)
    print('Project found:', project.name)
    
    # List branches
    branches = project.branches.list()
    print('Branches:', [b.name for b in branches])
    
    # Try to list root directory files
    files = project.repository_tree(ref='master')
    print('Root directory files:', [f['name'] for f in files])
    
except Exception as e:
    print('Error:', str(e))