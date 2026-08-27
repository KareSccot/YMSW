from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from services.user_management.user_service import UserService

router = APIRouter()
user_service = UserService()

# Request models
class CreateUserRequest(BaseModel):
    email: str
    username: str
    name: str
    password: str

class UserListRequest(BaseModel):
    active: bool = True

# Response models
class UserResponse(BaseModel):
    id: int
    username: str
    name: str
    email: str
    state: str
    web_url: str

@router.post("/", response_model=UserResponse)
async def create_user(request: CreateUserRequest):
    """Create a new GitLab user"""
    try:
        user = user_service.create_user(
            request.email,
            request.username,
            request.name,
            request.password
        )
        return UserResponse(
            id=user.id,
            username=user.username,
            name=user.name,
            email=user.email,
            state=user.state,
            web_url=user.web_url
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/{user_id}", response_model=UserResponse)
async def get_user(user_id: int | str):
    """Get user details by ID or username"""
    try:
        user = user_service.get_user(user_id)
        return UserResponse(
            id=user.id,
            username=user.username,
            name=user.name,
            email=user.email,
            state=user.state,
            web_url=user.web_url
        )
    except Exception as e:
        raise HTTPException(status_code=404, detail=str(e))

@router.get("/", response_model=list[UserResponse])
async def list_users(active: bool = True):
    """List all users"""
    try:
        users = user_service.list_users(active)
        return [
            UserResponse(
                id=user.id,
                username=user.username,
                name=user.name,
                email=user.email,
                state=user.state,
                web_url=user.web_url
            ) for user in users
        ]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))