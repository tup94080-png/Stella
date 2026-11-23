const DEFAULT_GGUF_PATH = "/sdcard/Download/model.gguf";
let LOCAL_SERVER_URL = localStorage.getItem('termux_bridge_url') || "http://127.0.0.1:5004";

function getSavedGgufPath() {
    return localStorage.getItem('custom_gguf_path') || DEFAULT_GGUF_PATH;
}

async function setGgufModelPath(newPath) {
    const cleanPath = (newPath || "").trim() || DEFAULT_GGUF_PATH;
    localStorage.setItem('custom_gguf_path', cleanPath);
    try {
        const response = await fetch(LOCAL_SERVER_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action: "set_model_path", path: cleanPath })
        });
        return await response.json();
    } catch (err) {
        return { error: "Bridge connection failed while updating model path." };
    }
}

async function runTermuxCommand(command) {
    try {
        const response = await fetch(LOCAL_SERVER_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action: "run_local_ai_shell", prompt: command })
        });
        return await response.json();
    } catch (err) {
        return { error: "Bridge connection failed." };
    }
}

async function listDirectory(pathStr) {
    try {
        const response = await fetch(LOCAL_SERVER_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action: "list_dir", path: pathStr })
        });
        return await response.json();
    } catch (err) {
        return { error: "Failed to load directory." };
    }
}
