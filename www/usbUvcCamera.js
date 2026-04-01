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

    refocus: function(options, success, error) {
        exec(success, error, 'UsbUvcCamera', 'refocus', [options || {}]);
    },

    applyStableCameraProfile: function(options, success, error) {
        exec(success, error, 'UsbUvcCamera', 'applyStableCameraProfile', [options || {}]);
    }
};
