use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::{HashMap, HashSet},
    path::{Path, PathBuf},
    process::Stdio,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::Duration,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    process::{Child, ChildStdin, Command},
    sync::{oneshot, Mutex},
    time::timeout,
};

type ReplySender = oneshot::Sender<Result<Value, String>>;
type PendingReplies = Arc<Mutex<HashMap<String, ReplySender>>>;

#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AndroidDevice {
    serial: String,
    state: String,
    model: String,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum AndroidAction {
    Inspect,
    OpenPicker,
    SelectSession,
    Prepare,
    Start,
    Stop,
    Status,
    AcknowledgeSubmission,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AndroidConfig {
    event_title: String,
    session: String,
    tier: String,
    quantity: u32,
    buyer_labels: Vec<String>,
    #[serde(default)]
    auto_submit: bool,
    start_at: Option<u64>,
    timeout_seconds: u32,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AndroidRequest {
    action: AndroidAction,
    device_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    config: Option<AndroidConfig>,
    #[serde(skip_serializing_if = "Option::is_none")]
    event_title: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    session: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    prepared_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    attempt_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    acknowledged: Option<bool>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct DriverReply {
    request_id: String,
    ok: bool,
    data: Option<Value>,
    error: Option<String>,
}

struct Driver {
    stdin: Mutex<Option<ChildStdin>>,
    child: Mutex<Child>,
    pending: PendingReplies,
    alive: Arc<AtomicBool>,
    inspected_devices: Mutex<HashSet<String>>,
}

#[derive(Default)]
pub struct AndroidManager {
    driver: Mutex<Option<Arc<Driver>>>,
    closing: AtomicBool,
}

fn parse_devices(output: &str) -> Vec<AndroidDevice> {
    output
        .lines()
        .filter_map(|line| {
            let mut fields = line.split_whitespace();
            let serial = fields.next()?;
            let state = fields.next()?;
            if !matches!(
                state,
                "device" | "offline" | "unauthorized" | "recovery" | "sideload"
            ) {
                return None;
            }
            let model = fields
                .find_map(|field| field.strip_prefix("model:"))
                .unwrap_or("")
                .replace('_', " ");
            Some(AndroidDevice {
                serial: serial.into(),
                state: state.into(),
                model,
            })
        })
        .collect()
}

fn executable(name: &str) -> Option<PathBuf> {
    let mut directories: Vec<PathBuf> = std::env::var_os("PATH")
        .map(|paths| std::env::split_paths(&paths).collect())
        .unwrap_or_default();
    if let Some(home) = tauri::api::path::home_dir() {
        directories.extend([
            home.join(".local/bin"),
            home.join(".cargo/bin"),
            home.join("Library/Android/sdk/platform-tools"),
            home.join("Android/Sdk/platform-tools"),
        ]);
    }
    for variable in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
        if let Some(root) = std::env::var_os(variable) {
            directories.push(PathBuf::from(root).join("platform-tools"));
        }
    }
    directories.extend([
        PathBuf::from("/opt/homebrew/bin"),
        PathBuf::from("/usr/local/bin"),
    ]);
    let file = if cfg!(windows) {
        format!("{name}.exe")
    } else {
        name.into()
    };
    directories
        .into_iter()
        .map(|dir| dir.join(&file))
        .find(|path| path.is_file())
}

fn is_macos_app_bundle(executable: &Path) -> bool {
    executable.parent().and_then(Path::file_name) == Some(std::ffi::OsStr::new("MacOS"))
        && executable
            .parent()
            .and_then(Path::parent)
            .and_then(Path::file_name)
            == Some(std::ffi::OsStr::new("Contents"))
        && executable
            .parent()
            .and_then(Path::parent)
            .and_then(Path::parent)
            .and_then(Path::extension)
            == Some(std::ffi::OsStr::new("app"))
}

fn driver_candidates(
    bundled: bool,
    resources: Vec<PathBuf>,
    development: Option<PathBuf>,
) -> Vec<PathBuf> {
    let mut candidates = Vec::new();
    if !bundled {
        candidates.extend(development);
    }
    candidates.extend(resources);
    candidates
}

fn driver_script(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let executable = std::env::current_exe().map_err(|_| "无法确定应用运行位置。")?;
    let mut resources = Vec::new();
    for resource in ["_up_/tools/android/driver.py", "tools/android/driver.py"] {
        if let Some(path) = app.path_resolver().resolve_resource(resource) {
            resources.push(path);
        }
    }
    let development = cfg!(debug_assertions)
        .then(|| Path::new(env!("CARGO_MANIFEST_DIR")).join("../tools/android/driver.py"));
    driver_candidates(is_macos_app_bundle(&executable), resources, development)
        .into_iter()
        .find(|path| path.is_file())
        .ok_or_else(|| "缺少安卓驱动文件，请重新构建 Tickets。".into())
}

fn validate_request(request: &AndroidRequest) -> Result<(), String> {
    let serial = &request.device_id;
    if serial.is_empty()
        || serial.len() > 128
        || !serial
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"-_.:".contains(&byte))
    {
        return Err("请选择有效的安卓设备。".into());
    }
    if request.action == AndroidAction::AcknowledgeSubmission {
        if request.acknowledged != Some(true)
            || !request.attempt_id.as_deref().map(|id| {
                id.len() == 32 && id.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            }).unwrap_or(false)
        {
            return Err("请先在手机核实订单，再明确确认本次提交记录。".into());
        }
    } else if request.attempt_id.is_some() || request.acknowledged.is_some() {
        return Err("订单核实字段只能用于人工确认操作。".into());
    }
    let valid_label = |text: &str| {
        !text.trim().is_empty() && text.chars().count() <= 300 && !text.contains(['\n', '\r', '\0'])
    };
    if matches!(
        request.action,
        AndroidAction::OpenPicker | AndroidAction::SelectSession
    ) && !request
        .event_title
        .as_deref()
        .map(valid_label)
        .unwrap_or(false)
    {
        return Err("打开选票前请先读取并确认演出标题。".into());
    }
    if request.action == AndroidAction::SelectSession
        && !request.session.as_deref().map(valid_label).unwrap_or(false)
    {
        return Err("请选择有效的演出场次。".into());
    }
    if matches!(
        request.action,
        AndroidAction::Prepare | AndroidAction::Start
    ) {
        let config = request.config.as_ref().ok_or("缺少演练配置。")?;
        if ![&config.event_title, &config.session, &config.tier]
            .iter()
            .all(|value| valid_label(value))
        {
            return Err("请明确选择演出、场次和票档。".into());
        }
        if !(1..=20).contains(&config.quantity) || !(10..=600).contains(&config.timeout_seconds) {
            return Err("票数或演练超时时间无效。".into());
        }
        if config.buyer_labels.len() > 20
            || (!config.buyer_labels.is_empty() && config.buyer_labels.len() != config.quantity as usize)
            || config.buyer_labels.iter().any(|value| !valid_label(value))
            || config.buyer_labels.iter().map(|value| value.chars().filter(|c| !c.is_whitespace()).collect::<String>())
                .collect::<HashSet<_>>().len() != config.buyer_labels.len()
        {
            return Err("观演人选择无效。".into());
        }
        if request.action == AndroidAction::Prepare && config.start_at.is_some() {
            return Err("准备待命时不能设置启动时间，请在准备完成后安排启动。".into());
        }
    }
    if request.action == AndroidAction::Start
        && !request
            .prepared_id
            .as_deref()
            .map(|id| valid_label(id) && id.chars().count() <= 100)
            .unwrap_or(false)
    {
        return Err("缺少有效的准备凭证，请先完成准备待命。".into());
    }
    Ok(())
}

fn validate_inspected_device(
    request: &AndroidRequest,
    inspected_devices: &HashSet<String>,
) -> Result<(), String> {
    if matches!(
        request.action,
        AndroidAction::OpenPicker
            | AndroidAction::SelectSession
            | AndroidAction::Prepare
            | AndroidAction::Start
    ) && !inspected_devices.contains(&request.device_id)
    {
        return Err("请先读取这台手机的当前页面，再开始操作。".into());
    }
    Ok(())
}

fn request_timeout(action: AndroidAction) -> Duration {
    Duration::from_secs(match action {
        AndroidAction::Status | AndroidAction::Stop | AndroidAction::AcknowledgeSubmission => 8,
        AndroidAction::Inspect | AndroidAction::OpenPicker | AndroidAction::SelectSession => 100,
        AndroidAction::Prepare | AndroidAction::Start => 15,
    })
}

async fn fail_pending(pending: &PendingReplies, message: &str) {
    for (_, sender) in pending.lock().await.drain() {
        let _ = sender.send(Err(message.to_owned()));
    }
}

async fn route_reply(line: &str, pending: &PendingReplies) -> Result<bool, String> {
    let reply: DriverReply =
        serde_json::from_str(line).map_err(|_| "安卓驱动返回了无法识别的数据。")?;
    if reply.request_id.is_empty() || reply.request_id.len() > 80 {
        return Err("安卓驱动响应缺少有效请求编号。".into());
    }
    let result = if reply.ok {
        match reply.data {
            Some(value) if value.is_object() => Ok(value),
            _ => return Err("安卓驱动响应缺少状态数据。".into()),
        }
    } else {
        Err(reply
            .error
            .unwrap_or_else(|| "安卓驱动请求失败。".into())
            .chars()
            .take(500)
            .collect())
    };
    if let Some(sender) = pending.lock().await.remove(&reply.request_id) {
        let _ = sender.send(result);
        Ok(true)
    } else {
        Ok(false)
    }
}

impl Driver {
    async fn spawn(app: &tauri::AppHandle) -> Result<Arc<Self>, String> {
        let uv = executable("uv").ok_or("未找到 uv，请先安装 Python 运行工具 uv。")?;
        let adb = executable("adb").ok_or("未找到 ADB，请先安装 Android platform-tools。")?;
        let script = driver_script(app)?;
        let working_directory = app
            .path_resolver()
            .app_cache_dir()
            .ok_or("无法找到应用缓存目录。")?
            .join("android-runtime");
        std::fs::create_dir_all(&working_directory).map_err(|_| "无法创建安卓驱动缓存目录。")?;
        // Submission fences must survive cache cleanup and desktop restarts.
        let state_directory = app.path_resolver().app_data_dir()
            .ok_or("无法找到应用数据目录，不能启用安卓执行。")?
            .join("android-submissions");
        let mut path: Vec<PathBuf> = std::env::var_os("PATH")
            .map(|value| std::env::split_paths(&value).collect())
            .unwrap_or_default();
        if let Some(directory) = adb.parent() {
            path.insert(0, directory.into());
        }
        let mut command = Command::new(uv);
        command
            .args(["run", "--offline", "--no-project", "--quiet"])
            .arg(&script)
            .env("PYTHONUNBUFFERED", "1")
            .env("TICKETS_ADB", adb)
            .env("TICKETS_ANDROID_STATE_DIR", state_directory)
            .env(
                "PATH",
                std::env::join_paths(path).map_err(|_| "无法设置安卓驱动运行路径。")?,
            )
            .current_dir(working_directory)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true);
        #[cfg(unix)]
        {
            use std::os::unix::process::CommandExt;
            command.as_std_mut().process_group(0);
        }
        let mut child = command
            .spawn()
            .map_err(|error| format!("无法启动安卓驱动：{error}"))?;
        let stdin = child.stdin.take().ok_or("安卓驱动输入管道不可用。")?;
        let stdout = child.stdout.take().ok_or("安卓驱动输出管道不可用。")?;
        let pending = Arc::new(Mutex::new(HashMap::new()));
        let alive = Arc::new(AtomicBool::new(true));
        let driver = Arc::new(Self {
            stdin: Mutex::new(Some(stdin)),
            child: Mutex::new(child),
            pending: pending.clone(),
            alive: alive.clone(),
            inspected_devices: Mutex::new(HashSet::new()),
        });
        let weak_driver = Arc::downgrade(&driver);
        tauri::async_runtime::spawn(async move {
            let mut lines = BufReader::new(stdout).lines();
            let failure = loop {
                match lines.next_line().await {
                    Ok(Some(line)) if line.is_empty() => continue,
                    Ok(Some(line)) if line.len() <= 1024 * 1024 => {
                        if let Err(error) = route_reply(&line, &pending).await {
                            break error;
                        }
                    }
                    Ok(Some(_)) => break "安卓驱动返回的数据过大，已中断。".into(),
                    _ => break "安卓驱动已断开。请重新读取手机页面，不会自动恢复演练。".into(),
                }
            };
            alive.store(false, Ordering::Release);
            fail_pending(&pending, &failure).await;
            if let Some(driver) = weak_driver.upgrade() {
                driver.shutdown().await;
            }
        });
        Ok(driver)
    }

    async fn request(&self, request: &AndroidRequest) -> Result<Value, String> {
        if !self.alive.load(Ordering::Acquire) {
            return Err("安卓驱动已断开，请重新读取页面。".into());
        }
        let request_id = uuid::Uuid::new_v4().to_string();
        let mut payload = serde_json::to_value(request).map_err(|_| "安卓请求格式无效。")?;
        payload["requestId"] = json!(request_id);
        let mut line = serde_json::to_vec(&payload).map_err(|_| "无法编码安卓请求。")?;
        line.push(b'\n');
        let (sender, receiver) = oneshot::channel();
        self.pending.lock().await.insert(request_id.clone(), sender);
        let write_result = timeout(Duration::from_secs(3), async {
            let mut stdin = self.stdin.lock().await;
            let stdin = stdin.as_mut().ok_or("安卓驱动已关闭。")?;
            stdin
                .write_all(&line)
                .await
                .map_err(|_| "无法发送安卓请求。")?;
            stdin.flush().await.map_err(|_| "无法刷新安卓请求。")
        })
        .await;
        if !matches!(write_result, Ok(Ok(()))) {
            self.pending.lock().await.remove(&request_id);
            self.shutdown().await;
            return Err("安卓驱动通信中断，演练已停止。请重新读取页面。".into());
        }
        match timeout(request_timeout(request.action), receiver).await {
            Ok(Ok(result)) => result,
            Ok(Err(_)) => Err("安卓驱动已断开，请重新读取页面。".into()),
            Err(_) => {
                self.pending.lock().await.remove(&request_id);
                self.shutdown().await;
                Err("安卓驱动响应超时，已停止驱动。请检查手机后重新读取页面。".into())
            }
        }
    }

    async fn shutdown(&self) {
        self.shutdown_with_grace(Duration::from_secs(12)).await;
    }

    async fn shutdown_with_grace(&self, grace: Duration) {
        self.alive.store(false, Ordering::Release);
        self.stdin.lock().await.take();
        fail_pending(&self.pending, "安卓驱动已停止。").await;
        let mut child = self.child.lock().await;
        if timeout(grace, child.wait()).await.is_err() {
            #[cfg(unix)]
            if let Some(pid) = child.id() {
                let mut kill = Command::new("/bin/kill");
                kill.args(["-KILL", "--", &format!("-{pid}")])
                    .stdin(Stdio::null())
                    .stdout(Stdio::null())
                    .stderr(Stdio::null())
                    .kill_on_drop(true);
                let _ = timeout(Duration::from_secs(1), kill.status()).await;
            }
            let _ = child.kill().await;
            let _ = child.wait().await;
        }
    }
}

impl AndroidManager {
    pub async fn shutdown(&self) {
        self.closing.store(true, Ordering::Release);
        if let Some(driver) = self.driver.lock().await.take() {
            driver.shutdown().await;
        }
    }

    async fn request(
        &self,
        app: &tauri::AppHandle,
        request: AndroidRequest,
    ) -> Result<Value, String> {
        validate_request(&request)?;
        if self.closing.load(Ordering::Acquire) {
            return Err("Tickets 正在退出。".into());
        }
        let driver = {
            let mut slot = self.driver.lock().await;
            if self.closing.load(Ordering::Acquire) {
                return Err("Tickets 正在退出。".into());
            }
            if slot
                .as_ref()
                .map(|driver| !driver.alive.load(Ordering::Acquire))
                .unwrap_or(false)
            {
                if let Some(previous) = slot.take() {
                    previous.shutdown().await;
                }
            }
            if slot.is_none() {
                match request.action {
                    // Reading status must surface durable submission fences after restart.
                    // Spawning the driver alone does not connect to or operate the phone.
                    AndroidAction::Inspect | AndroidAction::Status | AndroidAction::Stop
                        | AndroidAction::AcknowledgeSubmission => *slot = Some(Driver::spawn(app).await?),
                    _ => return Err("请先读取手机页面。驱动重启后不会自动恢复演练。".into()),
                }
            }
            slot.as_ref().unwrap().clone()
        };
        validate_inspected_device(&request, &*driver.inspected_devices.lock().await)?;
        let result = driver.request(&request).await?;
        if request.action == AndroidAction::Inspect {
            driver
                .inspected_devices
                .lock()
                .await
                .insert(request.device_id);
        }
        Ok(result)
    }
}

#[tauri::command]
pub async fn list_android_devices() -> Result<Vec<AndroidDevice>, String> {
    let adb = executable("adb").ok_or("未找到 ADB，请先安装 Android platform-tools。")?;
    let mut command = Command::new(adb);
    command.args(["devices", "-l"]).kill_on_drop(true);
    let output = timeout(Duration::from_secs(8), command.output())
        .await
        .map_err(|_| "读取安卓设备超时，请检查 USB 连接。")?
        .map_err(|_| "无法执行 ADB，请检查安装。")?;
    if !output.status.success() {
        return Err("ADB 未能列出设备，请检查 USB 调试。".into());
    }
    Ok(parse_devices(&String::from_utf8_lossy(&output.stdout)))
}

#[tauri::command]
pub async fn android_request(
    app: tauri::AppHandle,
    manager: tauri::State<'_, AndroidManager>,
    request: AndroidRequest,
) -> Result<Value, String> {
    manager.request(&app, request).await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid_config() -> Value {
        json!({
            "eventTitle": "目标演出", "session": "10月17日 19:30",
            "tier": "看台380元", "quantity": 1, "buyerLabels": [], "autoSubmit": false,
            "startAt": null, "timeoutSeconds": 120
        })
    }

    #[test]
    fn buyer_selection_defaults_or_matches_ticket_count_without_duplicates() {
        let mut payload = json!({"action":"prepare", "deviceId":"device-1", "config":valid_config()});
        assert!(validate_request(&request_from(payload.clone())).is_ok());
        payload["config"]["buyerLabels"] = json!(["测试甲"]);
        assert!(validate_request(&request_from(payload.clone())).is_ok());
        for buyers in [json!(["测试甲", "测试乙"]), json!([""]), json!(["a\nb"])] {
            payload["config"]["buyerLabels"] = buyers;
            assert!(validate_request(&request_from(payload.clone())).is_err());
        }
        payload["config"]["quantity"] = json!(2);
        payload["config"]["buyerLabels"] = json!(["测试甲", "测 试甲"]);
        assert!(validate_request(&request_from(payload.clone())).is_err());
        payload["config"]["buyerLabels"] = json!(["测试甲", "测试乙"]);
        assert!(validate_request(&request_from(payload)).is_ok());
    }

    #[test]
    fn auto_submit_is_explicit_boolean_and_survives_the_bridge() {
        let mut payload = json!({"action":"prepare", "deviceId":"device-1", "config":valid_config()});
        payload["config"]["autoSubmit"] = json!(true);
        let request: AndroidRequest = serde_json::from_value(payload.clone()).unwrap();
        assert!(request.config.as_ref().unwrap().auto_submit);
        assert_eq!(serde_json::to_value(&request).unwrap()["config"]["autoSubmit"], true);
        payload["config"]["autoSubmit"] = json!("true");
        assert!(serde_json::from_value::<AndroidRequest>(payload).is_err());
        let request: AndroidRequest = serde_json::from_value(json!({"action":"prepare", "deviceId":"device-1", "config":valid_config()})).unwrap();
        assert!(!request.config.unwrap().auto_submit);
    }

    fn request_from(value: Value) -> AndroidRequest {
        serde_json::from_value(value).unwrap()
    }

    #[test]
    fn submission_acknowledgement_requires_explicit_matching_attempt_fields() {
        let payload = json!({"action":"acknowledge_submission", "deviceId":"device-1", "attemptId":"a".repeat(32), "acknowledged":true});
        let request: AndroidRequest = serde_json::from_value(payload.clone()).expect("acknowledgement action");
        assert!(validate_request(&request).is_ok());
        assert_eq!(serde_json::to_value(request).unwrap(), payload);
        for (field, value) in [("attemptId", json!("")), ("attemptId", json!("a\nb")), ("attemptId", json!("x".repeat(101))), ("attemptId", json!("a".repeat(31))), ("attemptId", json!("A".repeat(32))), ("attemptId", json!("g".repeat(32))), ("acknowledged", json!(false)), ("acknowledged", Value::Null)] {
            let mut invalid = payload.clone();
            invalid[field] = value;
            assert!(validate_request(&request_from(invalid)).is_err());
        }
        let mut wrong_action = payload;
        wrong_action["action"] = json!("start");
        wrong_action["config"] = valid_config();
        wrong_action["preparedId"] = json!("ready");
        assert!(validate_request(&request_from(wrong_action)).is_err());
    }

    #[test]
    fn prepare_requires_complete_configuration_without_scheduling() {
        let payload = json!({"action":"prepare", "deviceId":"device-1", "config":valid_config()});
        assert!(validate_request(&request_from(payload.clone())).is_ok());
        let mut scheduled = payload.clone();
        scheduled["config"]["startAt"] = json!(1_800_000_000_000_u64);
        assert!(validate_request(&request_from(scheduled)).is_err());
        for field in ["eventTitle", "session", "tier"] {
            let mut missing_selection = payload.clone();
            missing_selection["config"][field] = json!(" ");
            assert!(validate_request(&request_from(missing_selection)).is_err());
        }
        for (field, value) in [
            ("quantity", 0),
            ("quantity", 21),
            ("timeoutSeconds", 9),
            ("timeoutSeconds", 601),
        ] {
            let mut invalid = payload.clone();
            invalid["config"][field] = json!(value);
            assert!(validate_request(&request_from(invalid)).is_err());
        }
        assert!(validate_request(&request_from(
            json!({"action":"prepare", "deviceId":"device-1"})
        ))
        .is_err());
    }

    #[test]
    fn start_requires_bounded_prepared_id_and_preserves_scheduled_configuration() {
        let mut payload = json!({"action":"start", "deviceId":"device-1", "config":valid_config()});
        assert!(validate_request(&request_from(payload.clone())).is_err());
        for id in [
            "".to_string(),
            " ".into(),
            "invalid\nidentifier".into(),
            "x".repeat(101),
        ] {
            payload["preparedId"] = json!(id);
            assert!(validate_request(&request_from(payload.clone())).is_err());
        }
        payload["preparedId"] = json!("x".repeat(100));
        payload["config"]["startAt"] = json!(1_800_000_000_000_u64);
        let request = request_from(payload.clone());
        assert!(validate_request(&request).is_ok());
        assert_eq!(serde_json::to_value(request).unwrap(), payload);
    }

    #[test]
    fn session_selection_requires_event_identity_and_a_bounded_label() {
        let payload = json!({"action":"select_session", "deviceId":"device-1", "eventTitle":"目标演出", "session":"10月17日 19:30"});
        let request = request_from(payload.clone());
        assert!(validate_request(&request).is_ok());
        assert_eq!(serde_json::to_value(request).unwrap(), payload);
        for field in ["eventTitle", "session"] {
            for value in [
                Value::Null,
                json!(""),
                json!("\n场次"),
                json!("场".repeat(301)),
            ] {
                let mut invalid = payload.clone();
                invalid[field] = value;
                assert!(validate_request(&request_from(invalid)).is_err());
            }
        }
    }

    #[test]
    fn mutating_actions_require_inspection_of_the_same_device_in_this_driver() {
        let mut inspected = HashSet::new();
        inspected.insert("device-1".to_string());
        for action in ["open_picker", "select_session", "prepare", "start"] {
            let mut request = request_from(json!({"action":action, "deviceId":"device-2"}));
            assert!(validate_inspected_device(&request, &inspected).is_err());
            request.device_id = "device-1".into();
            assert!(validate_inspected_device(&request, &inspected).is_ok());
            assert!(validate_inspected_device(&request, &HashSet::new()).is_err());
        }
        for action in ["inspect", "status", "stop"] {
            let request = request_from(json!({"action":action, "deviceId":"device-2"}));
            assert!(validate_inspected_device(&request, &HashSet::new()).is_ok());
        }
        assert_eq!(
            request_timeout(AndroidAction::Prepare),
            Duration::from_secs(15)
        );
        assert_eq!(
            request_timeout(AndroidAction::Start),
            Duration::from_secs(15)
        );
        assert_eq!(
            request_timeout(AndroidAction::SelectSession),
            Duration::from_secs(100)
        );
        assert_eq!(request_timeout(AndroidAction::Stop), Duration::from_secs(8));
        assert_eq!(
            request_timeout(AndroidAction::Status),
            Duration::from_secs(8)
        );
    }

    #[tokio::test]
    async fn preparation_states_and_metrics_are_forwarded_without_field_loss() {
        let pending: PendingReplies = Arc::new(Mutex::new(HashMap::new()));
        for status in ["preparing", "ready"] {
            let (sender, receiver) = oneshot::channel();
            pending.lock().await.insert(status.into(), sender);
            let mut prepared_config = valid_config();
            prepared_config.as_object_mut().unwrap().remove("startAt");
            let data = json!({
                "deviceId":"device-1", "status":status, "phase":"selection",
                "prepared":{"id":"prepared-1", "at":123456, "readiness":"waiting_sale", "config":prepared_config},
                "metrics":{"readMs":340, "ocrMs":125}, "page":null,
                "logs":[], "updatedAt":123456
            });
            let line = json!({"requestId":status, "ok":true, "data":data}).to_string();
            assert!(route_reply(&line, &pending).await.unwrap());
            assert_eq!(receiver.await.unwrap().unwrap(), data);
        }
    }

    #[test]
    fn packaged_debug_app_only_uses_its_own_resources() {
        let exe = Path::new("/Applications/Tickets.app/Contents/MacOS/tickets");
        assert!(is_macos_app_bundle(exe));
        let resource = PathBuf::from(
            "/Applications/Tickets.app/Contents/Resources/_up_/tools/android/driver.py",
        );
        let development = PathBuf::from("/workspace/tools/android/driver.py");
        assert_eq!(
            driver_candidates(true, vec![resource.clone()], Some(development.clone())),
            vec![resource]
        );
        assert!(driver_candidates(true, vec![], Some(development.clone())).is_empty());
        assert!(!is_macos_app_bundle(Path::new(
            "/workspace/target/debug/tickets"
        )));
        assert_eq!(
            driver_candidates(false, vec![], Some(development.clone())),
            vec![development]
        );
    }

    #[test]
    fn parses_only_device_records_and_preserves_authorization_state() {
        let rows = parse_devices("List of devices attached\n* daemon started successfully *\nabc device usb:12 product:phone model:SEA_AL10 device:phone\ndef unauthorized usb:13\n10.0.0.2:5555 offline\n");
        assert_eq!(rows.len(), 3);
        assert_eq!(
            rows[0],
            AndroidDevice {
                serial: "abc".into(),
                state: "device".into(),
                model: "SEA AL10".into()
            }
        );
        assert_eq!(rows[1].state, "unauthorized");
        assert_eq!(rows[2].serial, "10.0.0.2:5555");
    }

    #[tokio::test]
    async fn old_or_mismatched_responses_cannot_complete_new_requests() {
        let pending: PendingReplies = Arc::new(Mutex::new(HashMap::new()));
        let (sender, mut receiver) = oneshot::channel();
        pending.lock().await.insert("new".into(), sender);
        assert!(!route_reply(
            r#"{"requestId":"old","ok":true,"data":{"status":"running"}}"#,
            &pending
        )
        .await
        .unwrap());
        assert!(matches!(
            receiver.try_recv(),
            Err(oneshot::error::TryRecvError::Empty)
        ));
        assert!(route_reply(
            r#"{"requestId":"new","ok":true,"data":{"status":"cancelled"}}"#,
            &pending
        )
        .await
        .unwrap());
        assert_eq!(receiver.await.unwrap().unwrap()["status"], "cancelled");
    }

    #[tokio::test]
    async fn concurrent_stop_and_inspect_responses_are_routed_independently() {
        let pending: PendingReplies = Arc::new(Mutex::new(HashMap::new()));
        let (inspect_sender, inspect_receiver) = oneshot::channel();
        let (stop_sender, stop_receiver) = oneshot::channel();
        pending
            .lock()
            .await
            .insert("inspect".into(), inspect_sender);
        pending.lock().await.insert("stop".into(), stop_sender);
        route_reply(
            r#"{"requestId":"stop","ok":true,"data":{"status":"cancelled"}}"#,
            &pending,
        )
        .await
        .unwrap();
        assert_eq!(stop_receiver.await.unwrap().unwrap()["status"], "cancelled");
        fail_pending(&pending, "driver ended").await;
        assert_eq!(inspect_receiver.await.unwrap().unwrap_err(), "driver ended");
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn shutdown_terminates_unresponsive_process_and_rejects_pending_requests() {
        use std::os::unix::process::CommandExt;
        let mut command = Command::new("/bin/sleep");
        command.arg("60").stdin(Stdio::piped()).kill_on_drop(true);
        command.as_std_mut().process_group(0);
        let mut child = command.spawn().unwrap();
        let stdin = child.stdin.take().unwrap();
        let pending: PendingReplies = Arc::new(Mutex::new(HashMap::new()));
        let (sender, receiver) = oneshot::channel();
        pending.lock().await.insert("pending".into(), sender);
        let driver = Driver {
            stdin: Mutex::new(Some(stdin)),
            child: Mutex::new(child),
            pending,
            alive: Arc::new(AtomicBool::new(true)),
            inspected_devices: Mutex::new(HashSet::new()),
        };
        timeout(
            Duration::from_secs(3),
            driver.shutdown_with_grace(Duration::from_millis(50)),
        )
        .await
        .unwrap();
        assert!(!driver.alive.load(Ordering::Acquire));
        assert!(driver.child.lock().await.try_wait().unwrap().is_some());
        assert!(receiver.await.unwrap().is_err());
    }

    #[test]
    fn rejects_actions_without_explicit_configuration() {
        let request: AndroidRequest =
            serde_json::from_value(json!({"action":"start","deviceId":"device-1"})).unwrap();
        assert!(validate_request(&request).is_err());
        let request: AndroidRequest =
            serde_json::from_value(json!({"action":"inspect","deviceId":"; touch /tmp/file"}))
                .unwrap();
        assert!(validate_request(&request).is_err());
    }
}
