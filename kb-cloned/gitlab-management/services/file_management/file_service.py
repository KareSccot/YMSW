from lib.gitlab_client import BaseGitLabClient
import base64

class FileService(BaseGitLabClient):
    """Service for managing files in GitLab repositories"""
    
    def __init__(self):
        super().__init__()
    
    def get_file(self, project_id, file_path, ref='main'):
        """Get file content from a project"""
        project = self.get_project(project_id)
        file = project.files.get(file_path=file_path, ref=ref)
        # Extract filename from file_path
        file_name = file_path.split('/')[-1]
        return {
            'file_name': file_name,
            'file_path': file.file_path,
            'content': base64.b64decode(file.content).decode('utf-8'),
            'last_commit_id': file.last_commit_id
        }
    
    def update_file(self, project_id, file_path, content, branch='main', commit_message='Update file'):
        """Update an existing file"""
        project = self.get_project(project_id)
        
        # Get the current file to get the last commit ID
        current_file = project.files.get(file_path=file_path, ref=branch)
        
        # Use direct HTTP request to update the file
        url = f"{self.gitlab_url}/api/v4/projects/{project_id}/repository/files/{file_path.replace('/', '%2F')}"
        headers = {
            'PRIVATE-TOKEN': self.gitlab_token,
            'Content-Type': 'application/json'
        }
        data = {
            'branch': branch,
            'content': content,
            'commit_message': commit_message,
            'last_commit_id': current_file.last_commit_id
        }
        
        import requests
        response = requests.put(url, json=data, headers=headers, verify=False)
        
        if response.status_code == 200:
            # After updating, get the file again to get the latest last_commit_id
            updated_file = project.files.get(file_path=file_path, ref=branch)
            
            # Extract filename from file_path
            file_name = file_path.split('/')[-1]
            
            return {
                'file_name': file_name,
                'file_path': file_path,
                'last_commit_id': updated_file.last_commit_id,
                'message': 'File updated successfully'
            }
        else:
            raise Exception(f'GitLab API error: {response.status_code} - {response.text}')
    
    def create_file(self, project_id, file_path, content, branch='main', commit_message='Create file'):
        """Create a new file"""
        project = self.get_project(project_id)
        
        new_file = project.files.create(
            {
                'file_path': file_path,
                'branch': branch,
                'content': content,
                'commit_message': commit_message
            }
        )
        
        # Extract filename from file_path
        file_name = file_path.split('/')[-1]
        return {
            'file_name': file_name,
            'file_path': new_file.file_path,
            'last_commit_id': new_file.last_commit_id,
            'message': 'File created successfully'
        }
    
    def delete_file(self, project_id, file_path, branch='main', commit_message='Delete file'):
        """Delete a file"""
        project = self.get_project(project_id)
        
        # Get the current file to get the last commit ID
        current_file = project.files.get(file_path=file_path, ref=branch)
        
        # Delete the file
        project.files.delete(
            {
                'file_path': file_path,
                'branch': branch,
                'commit_message': commit_message,
                'last_commit_id': current_file.last_commit_id
            }
        )
        
        return {
            'file_path': file_path,
            'message': 'File deleted successfully'
        }
    
    def list_files(self, project_id, path='', ref='main'):
        """List files in a directory"""
        project = self.get_project(project_id)
        files = project.repository_tree(path=path, ref=ref, recursive=False)
        
        return [
            {
                'name': file['name'],
                'path': file['path'],
                'type': file['type'],
                'mode': file['mode']
            } for file in files
        ]