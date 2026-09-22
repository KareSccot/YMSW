from lib.gitlab_client import BaseGitLabClient
import yaml
import base64
import requests

class VersionService(BaseGitLabClient):
    """Service for managing version updates in ArgoCD deployment files"""
    
    def __init__(self):
        super().__init__()
    
    def update_image_version(self, project_id, file_path, new_version, branch='master', commit_message='Update image version'):
        """Update the image tag in a deployment file using direct HTTP requests"""
        try:
            # Get the current file
            project = self.get_project(project_id)
            current_file = project.files.get(file_path=file_path, ref=branch)
            
            # Decode the file content
            content = base64.b64decode(current_file.content).decode('utf-8')
            
            # Parse YAML
            yaml_content = yaml.safe_load(content)
            
            # Update the image tag
            if 'image' in yaml_content and 'tag' in yaml_content['image']:
                old_version = yaml_content['image']['tag']
                yaml_content['image']['tag'] = new_version
                
                # Convert back to YAML
                updated_content = yaml.dump(yaml_content, default_flow_style=False)
                
                # Prepare API request
                url = f"{self.gitlab_url}/api/v4/projects/{project_id}/repository/files/{file_path.replace('/', '%2F')}"
                headers = {
                    'PRIVATE-TOKEN': self.gitlab_token,
                    'Content-Type': 'application/json'
                }
                data = {
                    'branch': branch,
                    'content': updated_content,
                    'commit_message': commit_message,
                    'last_commit_id': current_file.last_commit_id
                }
                
                # Send PUT request to update the file
                response = requests.put(url, json=data, headers=headers, verify=False)
                
                if response.status_code == 200:
                    # After updating, get the file again to get the latest last_commit_id
                    updated_file = project.files.get(file_path=file_path, ref=branch)
                    
                    return {
                        'file_path': file_path,
                        'old_version': old_version,
                        'new_version': new_version,
                        'last_commit_id': updated_file.last_commit_id,
                        'message': 'Image version updated successfully'
                    }
                else:
                    raise Exception(f'GitLab API error: {response.status_code} - {response.text}')
            else:
                raise ValueError('image.tag field not found in the file')
                
        except Exception as e:
            raise Exception(f'Failed to update image version: {str(e)}')
    
    def batch_update_versions(self, project_id, updates, branch='master', commit_message='Batch update image versions'):
        """Batch update image versions across multiple files"""
        results = []
        
        try:
            for update in updates:
                file_path = update.get('file_path')
                new_version = update.get('new_version')
                
                if not file_path or not new_version:
                    results.append({
                        'file_path': file_path,
                        'success': False,
                        'message': 'Missing file_path or new_version'
                    })
                    continue
                
                try:
                    # Use the single update method for each file
                    result = self.update_image_version(
                        project_id,
                        file_path,
                        new_version,
                        branch,
                        commit_message
                    )
                    
                    results.append({
                        'file_path': result['file_path'],
                        'old_version': result['old_version'],
                        'new_version': result['new_version'],
                        'last_commit_id': result['last_commit_id'],
                        'success': True,
                        'message': result['message']
                    })
                    
                except Exception as e:
                    results.append({
                        'file_path': file_path,
                        'success': False,
                        'message': f'Failed to update: {str(e)}'
                    })
            
            return {
                'total': len(updates),
                'successful': sum(1 for r in results if r['success']),
                'failed': sum(1 for r in results if not r['success']),
                'results': results
            }
            
        except Exception as e:
            raise Exception(f'Batch update failed: {str(e)}')
