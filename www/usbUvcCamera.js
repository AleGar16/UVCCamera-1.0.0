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

    listUsbDevices: function(success, error) {
        exec(success, error, 'UsbUvcCamera', 'listUsbDevices', []);
    },

    applyStableCameraProfile: function(options, success, error) {
        exec(success, error, 'UsbUvcCamera', 'applyStableCameraProfile', [options || {}]);
    }
};
