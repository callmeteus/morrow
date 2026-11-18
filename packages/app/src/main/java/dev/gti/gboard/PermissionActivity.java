package dev.gti.gboard;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.app.Activity;

import androidx.core.content.ContextCompat;

/**
 * Transparent activity that exists only to request the microphone permission
 * on behalf of the IME service (services cannot show the runtime dialog).
 * It finishes itself immediately after the request, returning to the keyboard.
 */
public class PermissionActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            finish();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 42);
        if (!isFinishing()) {
            finish();
        }
    }
}
