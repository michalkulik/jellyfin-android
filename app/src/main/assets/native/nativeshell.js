const features = [
    "castmenuhashchange",
    "clientsettings",
    "displaylanguage",
    "downloadmanagement",
    "exit",
    "externallinks",
    "filedownload",
    "fileinput",
    "htmlaudioautoplay",
    "htmlvideoautoplay",
    "multiserver",
    "physicalvolumecontrol",
    "remotecontrol",
    "subtitleappearancesettings",
    "subtitleburnsettings",
    "updatecheck"
];

const plugins = [
    'NavigationPlugin',
    'ExoPlayerPlugin',
    'ExternalPlayerPlugin',
    'MediaSegmentsPlugin'
];

// Add plugin loaders
for (const plugin of plugins) {
    window[plugin] = async () => {
        const pluginDefinition = await import(`/native/${plugin}.js`);
        return pluginDefinition[plugin];
    };
}

const { deviceId, deviceName, appName, appVersion } = JSON.parse(window.NativeInterface.getDeviceInformation());
const codecCaps = JSON.parse(window.NativeInterface.getCodecCapabilities());

window.NativeShell = {
    enableFullscreen() {
        window.NativeInterface.enableFullscreen();
    },

    disableFullscreen() {
        window.NativeInterface.disableFullscreen();
    },

    openUrl(url, target) {
        window.NativeInterface.openUrl(url);
    },

    updateMediaSession(mediaInfo) {
        window.NativeInterface.updateMediaSession(JSON.stringify(mediaInfo));
    },

    hideMediaSession() {
        window.NativeInterface.hideMediaSession();
    },

    updateVolumeLevel(value) {
        window.NativeInterface.updateVolumeLevel(value);
    },

    downloadFile(downloadInfo) {
        window.NativeInterface.downloadFiles(JSON.stringify([downloadInfo]));
    },

    downloadFiles(downloadInfo) {
        window.NativeInterface.downloadFiles(JSON.stringify(downloadInfo));
    },

    openDownloadManager() {
        window.NativeInterface.openDownloadManager();
    },

    /**
     * Returns a map of item id to the download state of that item, for example
     * { "d4c1...": "downloaded" }. State values: queued, converting, downloading, downloaded,
     * error, cancelled.
     */
    getDownloadInfo() {
        try {
            return JSON.parse(window.NativeInterface.getDownloadInfo());
        } catch (err) {
            console.error('Failed to read download info', err);
            return {};
        }
    },

    onDownloadStateChanged() {
        window.dispatchEvent(new Event('downloadstatechange'));
    },

    /**
     * Opens the native update prompt. Used by the update entry in the profile menu and by the
     * update button in the dashboard.
     */
    openUpdateDialog() {
        window.NativeInterface.openUpdateDialog();
    },

    /**
     * Asks the native side to check for updates. Nothing is shown when the installed version is
     * current, the prompt only follows when there is something to install.
     */
    checkForUpdates() {
        window.NativeInterface.checkForUpdates();
    },

    /**
     * Returns the state of the in-app updater, for example
     * { state: 'available', version: '0.3.8', versionCode: 30899, progress: 0 }.
     * State values: unknown, checking, uptodate, available, downloading, downloaded, failed.
     */
    getUpdateState() {
        try {
            return JSON.parse(window.NativeInterface.getUpdateState());
        } catch (err) {
            console.error('Failed to read the update state', err);
            return { state: 'unknown' };
        }
    },

    onUpdateStateChanged(state) {
        window.dispatchEvent(new CustomEvent('updatestatechange', { detail: state }));
    },

    openClientSettings() {
        window.NativeInterface.openClientSettings();
    },

    selectServer() {
        window.NativeInterface.openServerSelection();
    },

    getPlugins() {
        return plugins;
    },

    async execCast(action, args, callback) {
        this.castCallbacks = this.castCallbacks || {};
        this.castCallbacks[action] = callback;
        window.NativeInterface.execCast(action, JSON.stringify(args));
    },

    async castCallback(action, keep, err, result) {
        const callbacks = this.castCallbacks || {};
        const callback = callbacks[action];
        callback && callback(err || null, result);
        if (!keep) {
            delete callbacks[action];
        }
    }
};

function getDeviceProfile(profileBuilder, item) {
    return profileBuilder();
}

window.NativeShell.AppHost = {
    init() {},
    getDefaultLayout() {
        return "mobile";
    },
    supports(command) {
        command = command.toLowerCase();
        if (command === "chromecast") {
            return window.NativeInterface.hasChromecast();
        }
        return features.includes(command);
    },
    getDeviceProfile,
    deviceName() {
        return deviceName;
    },
    deviceId() {
        return deviceId;
    },
    appName() {
        return appName;
    },
    appVersion() {
        return appVersion;
    },
    exit() {
        window.NativeInterface.exitApp();
    }
};
