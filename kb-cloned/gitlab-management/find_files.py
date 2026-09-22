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

# Get the project
project = gl.projects.get(1053)

# Recursively find all files containing 'dfx-gateway-service' in their path
all_files = []

def find_files(path='', ref='master'):
    files = project.repository_tree(path=path, ref=ref, recursive=False)
    for file in files:
        if file['type'] == 'blob':
            # Check if the file path contains the search term
            if 'dfx-gateway-service' in file['path']:
                all_files.append(file['path'])
        elif file['type'] == 'tree':
            # Recursively search in subdirectories
            find_files(path=file['path'], ref=ref)

# Start the search
find_files(ref='master')

# Print the results
print('Found files containing "dfx-gateway-service":')
for file_path in all_files:
    print(f'  - {file_path}')

# If no files found, list all files to see the structure
if not all_files:
    print('\nAll files in the repository:')
    def list_all_files(path='', ref='master', indent=0):
        files = project.repository_tree(path=path, ref=ref, recursive=False)
        for file in files:
            print('  ' * indent + f'- {file["name"]} ({file["type"]})')
            if file['type'] == 'tree':
                list_all_files(path=file['path'], ref=ref, indent=indent + 1)
    list_all_files(ref='master')