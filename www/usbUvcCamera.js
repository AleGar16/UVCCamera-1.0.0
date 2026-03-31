var exec = require('cordova/exec');

module.exports = {
    open: function(options, success, error) {
        exec(success, error, 'UsbUvcCamera', 'open', [options || {}]);
    },

    close: function(success, error) {
        exec(success, error, 'UsbUvcCamera', 'close', []);
    },

    takePhoto: function(success, error) {
        exec(success, error, 'UsbUvcCamera', 'takePhoto', []);
    },

    recoverCamera: function(success, error) {
        exec(success, error, 'UsbUvcCamera', 'recoverCamera', []);
    },

    showPreview: function(options, success, error) {
        exec(success, error, 'UsbUvcCamera', 'showPreview', [options || {}]);
    },

    hidePreview: function(success, error) {
        exec(success, error, 'UsbUvcCamera', 'hidePreview', []);
    },

    updatePreviewBounds: function(options, success, error) {
        exec(success, error, 'UsbUvcCamera', 'updatePreviewBounds', [options || {}]);
    },

    listUsbDevices: function(success, error) {
        exec(success, error, 'UsbUvcCamera', 'listUsbDevices', []);
    },

    getCameraCapabilities: function(success, error) {
        exec(success, error, 'UsbUvcCamera', 'getCameraCapabilities', []);
    },

    inspectUvcDescriptors: function(success, error) {
        exec(success, error, 'UsbUvcCamera', 'inspectUvcDescriptors', []);
    },

    setAutoFocus: function(enabled, success, error) {
        exec(success, error, 'UsbUvcCamera', 'setAutoFocus', [!!enabled]);
    },

    setFocus: function(value, success, error) {
        exec(success, error, 'UsbUvcCamera', 'setFocus', [value]);
    },

    setZoom: function(value, success, error) {
        exec(success, error, 'UsbUvcCamera', 'setZoom', [value]);
    },

    setBrightness: function(value, success, error) {
        exec(success, error, 'UsbUvcCamera', 'setBrightness', [value]);
    },

    setContrast: function(value, success, error) {
        exec(success, error, 'UsbUvcCamera', 'setContrast', [value]);
    },

    setSharpness: function(value, success, error) {
        exec(success, error, 'UsbUvcCamera', 'setSharpness', [value]);
    },

    setGain: function(value, success, error) {
        exec(success, error, 'UsbUvcCamera', 'setGain', [value]);
    },

    setAutoExposure: function(enabled, success, error) {
        exec(success, error, 'UsbUvcCamera', 'setAutoExposure', [!!enabled]);
    },

    setExposure: function(value, success, error) {
        exec(success, error, 'UsbUvcCamera', 'setExposure', [value]);
    },

    setAutoWhiteBalance: function(enabled, success, error) {
        exec(success, error, 'UsbUvcCamera', 'setAutoWhiteBalance', [!!enabled]);
    },

    setWhiteBalance: function(value, success, error) {
        exec(success, error, 'UsbUvcCamera', 'setWhiteBalance', [value]);
    },

    applyStableCameraProfile: function(options, success, error) {
        exec(success, error, 'UsbUvcCamera', 'applyStableCameraProfile', [options || {}]);
    }
};
