package edu.ucla.cs.eventmap;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.Toast;

import com.firebase.ui.auth.AuthUI;
import com.firebase.ui.auth.ErrorCodes;
import com.firebase.ui.auth.IdpResponse;
import com.google.firebase.auth.FirebaseAuth;

import java.util.Arrays;
import java.util.List;

public class LoginActivity extends AppCompatActivity {
    final int RC_SIGN_IN = 123;
    private FirebaseAuth auth;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            List<AuthUI.IdpConfig> providers = Arrays.asList(
                    new AuthUI.IdpConfig.EmailBuilder().build(),
                    new AuthUI.IdpConfig.FacebookBuilder().build()
            );
            startActivityForResult(AuthUI.getInstance().createSignInIntentBuilder().setAvailableProviders(providers).build(), RC_SIGN_IN);
        }
        else
        {
            Intent intent = new Intent(this, MapsActivity.class);
            intent.putExtra("uid", auth.getCurrentUser().getUid());
            intent.putExtra("username", auth.getCurrentUser().getDisplayName());
            startActivity(intent);
            finish();
        }
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN) {
            IdpResponse response = IdpResponse.fromResultIntent(data);
            if (resultCode == RESULT_OK) {
                Intent intent = new Intent(this, MapsActivity.class);
                intent.putExtra("uid", auth.getCurrentUser().getUid());
                intent.putExtra("username", auth.getCurrentUser().getDisplayName());
                startActivity(intent);
                finish();
            }
            else {
                if (response == null) {
                    Toast.makeText(this, "USER CANCELLED", Toast.LENGTH_LONG).show();
                    return;
                }
                if (response.getError().getErrorCode() == ErrorCodes.NO_NETWORK) {
                    Toast.makeText(this, "NO NETWORK", Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
    }
}
