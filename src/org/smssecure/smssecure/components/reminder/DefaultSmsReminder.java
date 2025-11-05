package org.smssecure.smssecure.components.reminder;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Telephony;
import android.view.View;
import android.view.View.OnClickListener;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.Util;

public class DefaultSmsReminder extends Reminder {

  public DefaultSmsReminder(final Context context) {
    super(context.getString(R.string.reminder_header_sms_default_title),
          context.getString(R.string.reminder_header_sms_default_text_mandatory),
          context.getString(R.string.reminder_header_sms_default_button));

    final OnClickListener okListener = new OnClickListener() {
      @Override
      public void onClick(View v) {
        SilencePreferences.setPromptedDefaultSmsProvider(context, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          RoleManager roleManager = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
          if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS) && !roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
            Intent roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS);
            if (context instanceof Activity) {
              ((Activity) context).startActivityForResult(roleIntent, 0);
            } else {
              roleIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              context.startActivity(roleIntent);
            }
            return;
          }
        }
        Intent intent = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
        intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.getPackageName());
        context.startActivity(intent);
      }
    };
    final OnClickListener dismissListener = new OnClickListener() {
      @Override
      public void onClick(View v) {
        SilencePreferences.setPromptedDefaultSmsProvider(context, true);
      }
    };
    setOkListener(okListener);
    setDismissListener(dismissListener);
  }

  public static boolean isEligible(Context context) {
    final boolean isDefault = Util.isDefaultSmsProvider(context);
    if (isDefault) {
      SilencePreferences.setPromptedDefaultSmsProvider(context, false);
    }

    return !isDefault && !SilencePreferences.hasPromptedDefaultSmsProvider(context);
  }
}
