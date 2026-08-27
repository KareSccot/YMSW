import gitlab
import os
import yaml
import base64
from dotenv import load_dotenv

# Load environment variables
load_dotenv()

# Initialize GitLab client
gl = gitlab.Gitlab(
    os.getenv('GITLAB_URL'),
    private_token=os.getenv('GITLAB_TOKEN'),
    ssl_verify=False
)

# Get the project
project = gl.projects.get(1053)

# Get the file manager
files_manager = project.files

# Get the current file
file_path = "app-values/dev/atlas-platform/dfx-gateway-service/dev-sh.yaml"
branch = "feat/dfx-sciwriter"

# Try to create a new file instead of updating
# This might help us understand the correct API usage
new_file_path = "app-values/dev/atlas-platform/dfx-gateway-service/test-update.yaml"
try:
    print("Trying to create a test file...")
    # Create a simple test file
    test_content = "test: content"
    file = files_manager.create({
        'file_path': new_file_path,
        'branch': branch,
        'content': test_content,
        'commit_message': "Create test file"
    })
    print(f"Success! Created file: {file.file_path}")
    
    # Now try to update it
    print("\nTrying to update the test file...")
    updated_content = "test: updated content"
    
    # Get the file object
    file = files_manager.get(file_path=new_file_path, ref=branch)
    
    # Try different update methods
    
    # Method 1: Using files_manager.update with dict
    try:
        print("Method 1: Using files_manager.update with dict")
        updated_file = files_manager.update({
            'file_path': new_file_path,
            'branch': branch,
            'content': updated_content,
            'commit_message': "Update test file",
            'last_commit_id': file.last_commit_id
        })
        print(f"Success! Updated file: {updated_file.file_path}")
    except Exception as e:
        print(f"Error with Method 1: {str(e)}")
        print(f"Error type: {type(e)}")
    
    # Method 2: Using file.save with attributes
    try:
        print("\nMethod 2: Using file.save with attributes")
        file.content = updated_content.encode('utf-8')  # Make sure content is bytes
        file.save(branch=branch, commit_message="Update test file")
        print(f"Success! Updated file: {file.file_path}")
    except Exception as e:
        print(f"Error with Method 2: {str(e)}")
        print(f"Error type: {type(e)}")
    
    # Method 3: Using files_manager.update with keyword arguments
    try:
        print("\nMethod 3: Using files_manager.update with keyword arguments")
        updated_file = files_manager.update(
            file_path=new_file_path,
            branch=branch,
            content=updated_content,
            commit_message="Update test file",
            last_commit_id=file.last_commit_id
        )
        print(f"Success! Updated file: {updated_file.file_path}")
    except Exception as e:
        print(f"Error with Method 3: {str(e)}")
        print(f"Error type: {type(e)}")
    
    # Clean up: Delete the test file
    print("\nCleaning up: Deleting test file...")
    file.delete(branch=branch, commit_message="Delete test file")
    print("Test file deleted")
    
except Exception as e:
    print(f"Error: {str(e)}")
    print(f"Error type: {type(e)}")
