package com.capacitorjs.plugins.filesystem;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import com.capacitorjs.plugins.filesystem.exceptions.CopyFailedException;
import com.capacitorjs.plugins.filesystem.exceptions.DirectoryExistsException;
import com.capacitorjs.plugins.filesystem.exceptions.DirectoryNotFoundException;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.PluginRequestCodes;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import java.io.*;
import java.nio.charset.Charset;
import org.json.JSONException;

@CapacitorPlugin(
    name = "Filesystem",
    requestCodes = {
        PluginRequestCodes.FILESYSTEM_REQUEST_WRITE_FILE_PERMISSIONS,
        PluginRequestCodes.FILESYSTEM_REQUEST_WRITE_FOLDER_PERMISSIONS,
        PluginRequestCodes.FILESYSTEM_REQUEST_READ_FILE_PERMISSIONS,
        PluginRequestCodes.FILESYSTEM_REQUEST_READ_FOLDER_PERMISSIONS,
        PluginRequestCodes.FILESYSTEM_REQUEST_DELETE_FILE_PERMISSIONS,
        PluginRequestCodes.FILESYSTEM_REQUEST_DELETE_FOLDER_PERMISSIONS,
        PluginRequestCodes.FILESYSTEM_REQUEST_URI_PERMISSIONS,
        PluginRequestCodes.FILESYSTEM_REQUEST_STAT_PERMISSIONS,
        PluginRequestCodes.FILESYSTEM_REQUEST_RENAME_PERMISSIONS,
        PluginRequestCodes.FILESYSTEM_REQUEST_COPY_PERMISSIONS
    },
    permissions = {
        @Permission(permission = Manifest.permission.READ_EXTERNAL_STORAGE),
        @Permission(permission = Manifest.permission.WRITE_EXTERNAL_STORAGE)
    },
    permissionRequestCode = PluginRequestCodes.FILESYSTEM_REQUEST_ALL_PERMISSIONS
)
public class FilesystemPlugin extends Plugin {

    private Filesystem implementation;

    @Override
    public void load() {
        implementation = new Filesystem(getContext());
    }

    private static final String PERMISSION_DENIED_ERROR = "Unable to do file operation, user denied permission request";

    @PluginMethod
    public void readFile(PluginCall call) {
        saveCall(call);
        String path = call.getString("path");
        String directory = getDirectoryParameter(call);
        String encoding = call.getString("encoding");

        Charset charset = implementation.getEncoding(encoding);
        if (encoding != null && charset == null) {
            call.reject("Unsupported encoding provided: " + encoding);
            return;
        }

        if (
            !isPublicDirectory(directory) ||
            isStoragePermissionGranted(
                PluginRequestCodes.FILESYSTEM_REQUEST_READ_FILE_PERMISSIONS,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        ) {
            try {
                String dataStr = implementation.readFile(path, directory, charset);
                JSObject ret = new JSObject();
                ret.putOpt("data", dataStr);
                call.resolve(ret);
            } catch (FileNotFoundException ex) {
                call.reject("File does not exist", ex);
            } catch (IOException ex) {
                call.reject("Unable to read file", ex);
            } catch (JSONException ex) {
                call.reject("Unable to return value for reading file", ex);
            }
        }
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        saveCall(call);
        String path = call.getString("path");
        String data = call.getString("data");
        Boolean recursive = call.getBoolean("recursive", false);

        if (path == null) {
            Logger.error(getLogTag(), "No path or filename retrieved from call", null);
            call.reject("NO_PATH");
            return;
        }

        if (data == null) {
            Logger.error(getLogTag(), "No data retrieved from call", null);
            call.reject("NO_DATA");
            return;
        }

        String directory = getDirectoryParameter(call);
        if (directory != null) {
            if (
                !isPublicDirectory(directory) ||
                isStoragePermissionGranted(
                    PluginRequestCodes.FILESYSTEM_REQUEST_WRITE_FILE_PERMISSIONS,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            ) {
                // create directory because it might not exist
                File androidDir = implementation.getDirectory(directory);
                if (androidDir != null) {
                    if (androidDir.exists() || androidDir.mkdirs()) {
                        // path might include directories as well
                        File fileObject = new File(androidDir, path);
                        if (fileObject.getParentFile().exists() || (recursive && fileObject.getParentFile().mkdirs())) {
                            saveFile(call, fileObject, data);
                        } else {
                            call.reject("Parent folder doesn't exist");
                        }
                    } else {
                        Logger.error(getLogTag(), "Not able to create '" + directory + "'!", null);
                        call.reject("NOT_CREATED_DIR");
                    }
                } else {
                    Logger.error(getLogTag(), "Directory ID '" + directory + "' is not supported by plugin", null);
                    call.reject("INVALID_DIR");
                }
            }
        } else {
            // check if file://
            Uri u = Uri.parse(path);
            if ("file".equals(u.getScheme())) {
                File fileObject = new File(u.getPath());
                // do not know where the file is being store so checking the permission to be secure
                // TODO to prevent permission checking we need a property from the call
                if (
                    isStoragePermissionGranted(
                        PluginRequestCodes.FILESYSTEM_REQUEST_WRITE_FILE_PERMISSIONS,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                ) {
                    if (fileObject.getParentFile().exists() || (recursive && fileObject.getParentFile().mkdirs())) {
                        saveFile(call, fileObject, data);
                    } else {
                        call.reject("Parent folder doesn't exist");
                    }
                }
            }
        }
    }

    private void saveFile(PluginCall call, File file, String data) {
        String encoding = call.getString("encoding");
        boolean append = call.getBoolean("append", false);

        Charset charset = implementation.getEncoding(encoding);
        if (encoding != null && charset == null) {
            call.reject("Unsupported encoding provided: " + encoding);
            return;
        }

        try {
            implementation.saveFile(file, data, charset, append);
            // update mediaStore index only if file was written to external storage
            if (isPublicDirectory(getDirectoryParameter(call))) {
                MediaScannerConnection.scanFile(getContext(), new String[] { file.getAbsolutePath() }, null, null);
            }
            Logger.debug(getLogTag(), "File '" + file.getAbsolutePath() + "' saved!");
            JSObject result = new JSObject();
            result.put("uri", Uri.fromFile(file).toString());
            call.resolve(result);
        } catch (IOException ex) {
            Logger.error(
                getLogTag(),
                "Creating file '" + file.getPath() + "' with charset '" + charset + "' failed. Error: " + ex.getMessage(),
                ex
            );
            call.reject("FILE_NOTCREATED");
        }
    }

    @PluginMethod
    public void appendFile(PluginCall call) {
        try {
            call.getData().putOpt("append", true);
        } catch (JSONException ex) {}

        this.writeFile(call);
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        saveCall(call);
        String file = call.getString("path");
        String directory = getDirectoryParameter(call);
        if (
            !isPublicDirectory(directory) ||
            isStoragePermissionGranted(
                PluginRequestCodes.FILESYSTEM_REQUEST_DELETE_FILE_PERMISSIONS,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        ) {
            try {
                boolean deleted = implementation.deleteFile(file, directory);
                if (!deleted) {
                    call.reject("Unable to delete file");
                } else {
                    call.resolve();
                }
            } catch (FileNotFoundException ex) {
                call.reject(ex.getMessage());
            }
        }
    }

    @PluginMethod
    public void mkdir(PluginCall call) {
        saveCall(call);
        String path = call.getString("path");
        String directory = getDirectoryParameter(call);
        boolean recursive = call.getBoolean("recursive", false).booleanValue();
        if (
            !isPublicDirectory(directory) ||
            isStoragePermissionGranted(
                PluginRequestCodes.FILESYSTEM_REQUEST_WRITE_FOLDER_PERMISSIONS,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        ) {
            try {
                boolean created = implementation.mkdir(path, directory, recursive);
                if (!created) {
                    call.reject("Unable to create directory, unknown reason");
                } else {
                    call.resolve();
                }
            } catch (DirectoryExistsException ex) {
                call.reject(ex.getMessage());
            }
        }
    }

    @PluginMethod
    public void rmdir(PluginCall call) {
        saveCall(call);
        String path = call.getString("path");
        String directory = getDirectoryParameter(call);
        Boolean recursive = call.getBoolean("recursive", false);

        File fileObject = implementation.getFileObject(path, directory);

        if (
            !isPublicDirectory(directory) ||
            isStoragePermissionGranted(
                PluginRequestCodes.FILESYSTEM_REQUEST_DELETE_FOLDER_PERMISSIONS,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        ) {
            if (!fileObject.exists()) {
                call.reject("Directory does not exist");
                return;
            }

            if (fileObject.isDirectory() && fileObject.listFiles().length != 0 && !recursive) {
                call.reject("Directory is not empty");
                return;
            }

            boolean deleted = false;

            try {
                implementation.deleteRecursively(fileObject);
                deleted = true;
            } catch (IOException ignored) {}

            if (!deleted) {
                call.reject("Unable to delete directory, unknown reason");
            } else {
                call.resolve();
            }
        }
    }

    @PluginMethod
    public void readdir(PluginCall call) {
        saveCall(call);
        String path = call.getString("path");
        String directory = getDirectoryParameter(call);

        if (
            !isPublicDirectory(directory) ||
            isStoragePermissionGranted(
                PluginRequestCodes.FILESYSTEM_REQUEST_READ_FOLDER_PERMISSIONS,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        ) {
            try {
                String[] files = implementation.readdir(path, directory);
                if (files != null) {
                    JSObject ret = new JSObject();
                    ret.put("files", JSArray.from(files));
                    call.resolve(ret);
                } else {
                    call.reject("Unable to read directory");
                }
            } catch (DirectoryNotFoundException ex) {
                call.reject(ex.getMessage());
            }
        }
    }

    @PluginMethod
    public void getUri(PluginCall call) {
        saveCall(call);
        String path = call.getString("path");
        String directory = getDirectoryParameter(call);

        File fileObject = implementation.getFileObject(path, directory);

        if (
            !isPublicDirectory(directory) ||
            isStoragePermissionGranted(PluginRequestCodes.FILESYSTEM_REQUEST_URI_PERMISSIONS, Manifest.permission.READ_EXTERNAL_STORAGE)
        ) {
            JSObject data = new JSObject();
            data.put("uri", Uri.fromFile(fileObject).toString());
            call.resolve(data);
        }
    }

    @PluginMethod
    public void stat(PluginCall call) {
        saveCall(call);
        String path = call.getString("path");
        String directory = getDirectoryParameter(call);

        File fileObject = implementation.getFileObject(path, directory);

        if (
            !isPublicDirectory(directory) ||
            isStoragePermissionGranted(PluginRequestCodes.FILESYSTEM_REQUEST_STAT_PERMISSIONS, Manifest.permission.READ_EXTERNAL_STORAGE)
        ) {
            if (!fileObject.exists()) {
                call.reject("File does not exist");
                return;
            }

            JSObject data = new JSObject();
            data.put("type", fileObject.isDirectory() ? "directory" : "file");
            data.put("size", fileObject.length());
            data.put("ctime", null);
            data.put("mtime", fileObject.lastModified());
            data.put("uri", Uri.fromFile(fileObject).toString());
            call.resolve(data);
        }
    }

    @PluginMethod
    public void rename(PluginCall call) {
        this._copy(call, true);
    }

    @PluginMethod
    public void copy(PluginCall call) {
        this._copy(call, false);
    }

    private void _copy(PluginCall call, Boolean doRename) {
        String from = call.getString("from");
        String to = call.getString("to");
        String directory = call.getString("directory");
        String toDirectory = call.getString("toDirectory");

        if (from == null || from.isEmpty() || to == null || to.isEmpty()) {
            call.reject("Both to and from must be provided");
            return;
        }
        if (isPublicDirectory(directory) || isPublicDirectory(toDirectory)) {
            if (doRename) {
                if (
                    !isStoragePermissionGranted(
                        PluginRequestCodes.FILESYSTEM_REQUEST_RENAME_PERMISSIONS,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                ) {
                    return;
                }
            } else {
                if (
                    !isStoragePermissionGranted(
                        PluginRequestCodes.FILESYSTEM_REQUEST_COPY_PERMISSIONS,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                ) {
                    return;
                }
            }
        }
        try {
            implementation.copy(from, directory, to, toDirectory, doRename);
        } catch (CopyFailedException ex) {
            call.reject(ex.getMessage());
        } catch (IOException ex) {
            call.reject("Unable to perform action: " + ex.getLocalizedMessage());
        }
    }

    /**
     * Checks the the given permission and requests them if they are not already granted.
     * @param permissionRequestCode the request code see {@link PluginRequestCodes}
     * @param permission the permission string
     * @return Returns true if the permission is granted and false if it is denied.
     */
    private boolean isStoragePermissionGranted(int permissionRequestCode, String permission) {
        if (hasPermission(permission)) {
            Logger.verbose(getLogTag(), "Permission '" + permission + "' is granted");
            return true;
        } else {
            Logger.verbose(getLogTag(), "Permission '" + permission + "' denied. Asking user for it.");
            pluginRequestPermissions(new String[] { permission }, permissionRequestCode);
            return false;
        }
    }

    /**
     * Reads the directory parameter from the plugin call
     * @param call the plugin call
     */
    private String getDirectoryParameter(PluginCall call) {
        return call.getString("directory");
    }

    /**
     * True if the given directory string is a public storage directory, which is accessible by the user or other apps.
     * @param directory the directory string.
     */
    private boolean isPublicDirectory(String directory) {
        return "DOCUMENTS".equals(directory) || "EXTERNAL_STORAGE".equals(directory);
    }

    @Override
    protected void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        Logger.debug(getLogTag(), "handling request perms result");

        if (getSavedCall() == null) {
            Logger.debug(getLogTag(), "No stored plugin call for permissions request result");
            return;
        }

        PluginCall savedCall = getSavedCall();

        for (int i = 0; i < grantResults.length; i++) {
            int result = grantResults[i];
            String perm = permissions[i];
            if (result == PackageManager.PERMISSION_DENIED) {
                Logger.debug(getLogTag(), "User denied storage permission: " + perm);
                savedCall.reject(PERMISSION_DENIED_ERROR);
                this.freeSavedCall();
                return;
            }
        }

        if (requestCode == PluginRequestCodes.FILESYSTEM_REQUEST_WRITE_FILE_PERMISSIONS) {
            this.writeFile(savedCall);
        } else if (requestCode == PluginRequestCodes.FILESYSTEM_REQUEST_WRITE_FOLDER_PERMISSIONS) {
            this.mkdir(savedCall);
        } else if (requestCode == PluginRequestCodes.FILESYSTEM_REQUEST_READ_FILE_PERMISSIONS) {
            this.readFile(savedCall);
        } else if (requestCode == PluginRequestCodes.FILESYSTEM_REQUEST_READ_FOLDER_PERMISSIONS) {
            this.readdir(savedCall);
        } else if (requestCode == PluginRequestCodes.FILESYSTEM_REQUEST_DELETE_FILE_PERMISSIONS) {
            this.deleteFile(savedCall);
        } else if (requestCode == PluginRequestCodes.FILESYSTEM_REQUEST_DELETE_FOLDER_PERMISSIONS) {
            this.rmdir(savedCall);
        } else if (requestCode == PluginRequestCodes.FILESYSTEM_REQUEST_URI_PERMISSIONS) {
            this.getUri(savedCall);
        } else if (requestCode == PluginRequestCodes.FILESYSTEM_REQUEST_STAT_PERMISSIONS) {
            this.stat(savedCall);
        } else if (requestCode == PluginRequestCodes.FILESYSTEM_REQUEST_RENAME_PERMISSIONS) {
            this.rename(savedCall);
        } else if (requestCode == PluginRequestCodes.FILESYSTEM_REQUEST_COPY_PERMISSIONS) {
            this.copy(savedCall);
        } else if (requestCode == PluginRequestCodes.FILESYSTEM_REQUEST_ALL_PERMISSIONS) {
            savedCall.resolve();
        }
        this.freeSavedCall();
    }
}