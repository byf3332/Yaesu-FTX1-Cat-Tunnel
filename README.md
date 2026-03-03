# CatTunnel for Yaesu FTX-1

An Android CAT → TCP forwarding tool for **Yaesu FTX-1**, primarily designed to work with **dtrac** to control the radio over a network connection.

> ⚠️ Note:  
> This repository contains a significant amount of code generated and organized with AI (ChatGPT) assistance. It is an experimental project.

---

## Current Support Status

- ✅ **Radio**: Yaesu **FTX-1**
- ✅ **Software**: **dtrac**
- ✅ **Connection Method**: USB → Android → TCP (LAN)
- ❌ Other Yaesu / non-Yaesu radios **not tested**
- ❌ Other CAT clients **not tested**

---

## How It Works (Brief)

```
FTX-1 (USB CAT)
│
Android Phone (CatTunnel)
│
dtrac (same LAN)
```


On Android, CatTunnel:

- Accesses the radio's CAT serial port via USB
- Starts a local TCP server
- Forwards raw byte streams bidirectionally between **TCP ⇄ CAT**

---

## Usage Instructions

### 1. CatTunnel (This App)

1. **Connect the FTX-1 to your Android phone using a USB data cable**
2. Open **CatTunnel**
3. Tap **TEST**
   - If you see `ID;` returning in the log (e.g., `ID0840;`), CAT is working properly
4. Tap **START**
   - CatTunnel will then run in the background (foreground service + persistent notification)
5. If the app does not prompt or auto-detect the USB device after plugging in, check USB permissions and try tapping **SCAN** to search manually

> ⚠️ Notes  
> - If you tap **START** without connecting the radio, it will indicate that it has not started  
> - After unplugging the USB cable, you must manually tap **STOP**, or remove the app from the recent apps list before reconnecting

---

### 2. dtrac Configuration

In **dtrac**:

1. **Radio Type**: Select **YAESU FTX-1**
2. **Radio Interface**: Select **Network**
3. **IP Address**: Enter the LAN IP of the phone running CatTunnel (if using dtrac on the same phone, you can enter `127.0.0.1`)
   - Example: `192.168.1.123`
4. **Port**: Enter the port configured in CatTunnel  
   - Default: `4532`
5. Click Connect

一个用于 **Yaesu FTX-1** 的 Android CAT → TCP 转发工具，主要用于配合 **dtrac** 通过网络方式控制电台。

> ⚠️ 说明：  
> 本仓库中**包含较多由 AI（ChatGPT）辅助生成与整理的代码**，属于实验性项目。

---

## 当前支持情况

- ✅ **电台**：Yaesu **FTX-1**
- ✅ **软件**：**dtrac**
- ✅ **连接方式**：USB → Android → TCP（局域网）
- ❌ 其他 Yaesu / 非 Yaesu 电台 **未测试**
- ❌ 其他 CAT 客户端 **未测试**


---

## 工作原理（简述）

```

FTX-1 (USB CAT)
│
Android 手机（CatTunnel）
│
dtrac（同一局域网）

```

CatTunnel 在 Android 上：
- 通过 USB 访问电台 CAT 串口
- 在本机启动一个 TCP Server
- 将 **TCP ⇄ CAT** 的原始字节流双向转发

---

## 使用方法

### 一、CatTunnel（本 App）

1. **用 USB 数据线将 FTX-1 连接到 Android 手机**
2. 打开 **CatTunnel**
3. 点击 **TEST**
   - 如果日志中看到 `ID;` 有返回（例如 `ID0840;`），说明 CAT 正常
4. 点击 **START**
   - 此时 CatTunnel 会在后台运行（前台服务 + 常驻通知）
5. 如果app没有在插线后弹窗或自动打开并自动识别usb设备，可检查USB权限并尝试点击 **SCAN** 进行扫描

> ⚠️ 注意  
> - 如果 **未插电台就点 START**，会提示尚未启动  
> - **拔掉 USB 线后**，需要手动点 **STOP**，或从后台清除应用后才能重新连接

---

### 二、dtrac 设置

在 **dtrac** 中：

1. **电台类型**：选择 **YAESU FTX-1**
2. **电台接口**：选择 **网络**
3. **IP 地址**：填写安装了Cat Tunnel手机的局域网 IP（如果和使用dtrac的是同一台手机，可以填写127.0.0.1）
   - 例如：`192.168.1.123`
4. **端口**：填写 CatTunnel 中设置的端口  
   - 默认：`4532`
5. 点击连接即可

