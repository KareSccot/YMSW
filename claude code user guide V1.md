# Setting up Claude Code with AI Gateway in VS Code

This guide walks you through installing the **Claude Code extension** in Visual Studio Code, and configuring the extension to use a enterprise model service (e.g., glm-5.1 from Alibaba Cloud’s DashScope) via company's AI Gateway endpoint (e.g. cn test endpoint).


# How to get started

## Part 1: Set up Claude Code in VS Code

### 1.1 Download and Install VS Code
If you don’t have Visual Studio Code installed, download it from the [official website](https://code.visualstudio.com/) and run the installer.

### 1.2 Install the Claude Code Extension
- Open VS Code.
- Go to the **Extensions** view by clicking the Extensions icon in the Activity Bar on the side or pressing `Ctrl+Shift+X` (Windows); `Command(⌘) + Shift(⇧) + X` (Mac) .
- In the search box, type `Claude Code for VS Code`.
- Find the extension and click **Install**.

### 1.3 Configure Claude Code Settings
After installation, open the Claude Code extension settings:
- Click the gear icon next to the extension in the Extensions list, or open the Command Palette (`Ctrl+Shift+P` / `Command(⌘) + Shift(⇧) + P`) and search for `Preferences: Open Settings (UI)`, then filter by `Claude Code`.
- Enable (check) the following options:
  - **Disable Login Prompt** – Prevents the login prompt from appearing.
  - **Hide Onboarding** – Hides the onboarding welcome screen.

These settings ensure a smoother start without unnecessary interruptions.

---

## Part 2: Configure Claude Code Environment Variables
Now we need to tell the Claude Code extension to use the AI Gateway instead of the default Anthropic API.
You would need to apply for an API key issued by AI Gateway first, check ioffice [guide](https://wuxibiologicsioffice-alidocs.dingtalk.com/i/nodes/ndMj49yWjXOLOrkAswj6nwNgJ3pmz5aA?utm_scene=person_space) for further info, once key received, you can continue with the configurations.


### 2.1 For VS Code Extension Users (settings.json)
Open the Command Palette (`Ctrl+Shift+P` / `Command(⌘) + Shift(⇧) + X` ) and select Preferences: Open Settings (JSON).
This opens the `settings.json` file where you can override extension settings.

Add or modify the `"claudeCode.environmentVariables"` section as shown below.
This sets the base URL to your local AI Gateway and forces all Claude models to use the same custom model (`glm-5.1`).

**For VS Code Extension Users**
```bash
[
    {
      "name": "ANTHROPIC_DEFAULT_SONNET_MODEL",
      "value": "glm-5.1"
    },
    {
      "name": "ANTHROPIC_DEFAULT_HAIKU_MODEL",
      "value": "glm-5.1"
    },
    {
      "name": "ANTHROPIC_DEFAULT_OPUS_MODEL",
      "value": "glm-5.1"
    },
    {
      "name": "CLAUDE_CODE_MAX_OUTPUT_TOKENS",
      "value": "98304"
    },
    {
      "name": "ANTHROPIC_BASE_URL",
      "value": "https://ai-gateway-test-cn.wuxibiologics.com"
    },
    {
      "name": "ANTHROPIC_AUTH_TOKEN",
      "value": "xxx-key"
    }
]
```

- Save the settings.json file.

### 2.2 Local Claude CLI Users (settings.json)
If you are using the standalone Claude CLI instead of the VS Code extension, edit the Claude Code settings file:

`~/.claude/settings.json`

On Windows, the file is typically located at:

`C:\Users\<username>\.claude\settings.json`

Add or update the "env" section as follows:

**For Local Claude CLI Users**
```json
{
  "env": {
    "ANTHROPIC_AUTH_TOKEN": "xxx-key", 
    "ANTHROPIC_BASE_URL": "https://ai-gateway-test-cn.wuxibiologics.com",
    "ANTHROPIC_DEFAULT_HAIKU_MODEL": "glm-5.1",
    "ANTHROPIC_DEFAULT_OPUS_MODEL": "glm-5.1",
    "ANTHROPIC_DEFAULT_SONNET_MODEL": "glm-5.1",
    "ANTHROPIC_MODEL": "glm-5.1",
    "ANTHROPIC_REASONING_MODEL": "glm-5.1",
    "CLAUDE_CODE_MAX_OUTPUT_TOKENS": "98304"
  },
  "includeCoAuthoredBy": false
}
```
- Save the settings.json file.

# Additional Notes
- **Windows Users**: The commands above use cmd. For PowerShell, the activation script path is llm-env\Scripts\Activate.ps1.
- **Mac Users**: installing Docker Platform requesting temporary admin access, please raise question in Mac Support Chat, or reach out to wendy.gong@wuxibiologics.com, using Docker will not require admin access.

- **API Key Security**: Never commit your real API key to version control. Consider using environment variables and ignore the file from git if the configurations are set within the project

- **Model Mapping**: You can see available model in [Model List](https://wuxibiologicsioffice-alidocs.dingtalk.com/i/nodes/GZLxjv9VGqx7xgmoF1L34n3P86EDybno?utm_scene=person_space&iframeQuery=viewId%3Dsq4cdtrj8j4rwwcu83xlc%26sheetId%3Dmf0bbn22e1w6esbrogfsd), and find tested and "Anthropic enabled" models.

- **Claude Code Environment Variables Configuration**: In `settings.json`, due to the output token limit of the glm-5.1 model, you should set `"CLAUDE_CODE_MAX_OUTPUT_TOKENS"` to `"98304"`. Adjust this value based on the maximum token limit of the model you are using, check [guide section](https://wuxibiologicsioffice-alidocs.dingtalk.com/i/nodes/NDoBb60VLQq4qXbDIBX7KobLJlemrZQ3?utm_scene=person_space&iframeQuery=anchorId%3Duu_moif05bb7xoa8ef6i6g).

Now you're ready to use Claude Code with an Enterprise LLM backend via AI Gateway!
