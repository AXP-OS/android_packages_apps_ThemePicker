/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.customization.picker.theme;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.android.customization.model.CustomizationManager.Callback;
import com.android.customization.model.theme.DefaultThemeProvider;
import com.android.customization.model.theme.OverlayManagerCompat;
import com.android.customization.model.theme.ThemeBundle;
import com.android.customization.model.theme.ThemeBundle.Builder;
import com.android.customization.model.theme.ThemeBundleProvider;
import com.android.customization.model.theme.ThemeManager;
import com.android.customization.model.theme.custom.ColorOptionsProvider;
import com.android.customization.model.theme.custom.CustomTheme;
import com.android.customization.model.theme.custom.CustomThemeManager;
import com.android.customization.model.theme.custom.FontOptionsProvider;
import com.android.customization.model.theme.custom.IconOptionsProvider;
import com.android.customization.model.theme.custom.ShapeOptionsProvider;
import com.android.customization.model.theme.custom.ThemeComponentOption;
import com.android.customization.model.theme.custom.ThemeComponentOption.ColorOption;
import com.android.customization.model.theme.custom.ThemeComponentOption.FontOption;
import com.android.customization.model.theme.custom.ThemeComponentOption.IconOption;
import com.android.customization.model.theme.custom.ThemeComponentOption.ShapeOption;
import com.android.customization.model.theme.custom.ThemeComponentOptionProvider;
import com.android.customization.module.CustomizationInjector;
import com.android.customization.module.ThemesUserEventLogger;
import com.android.customization.picker.theme.CustomThemeComponentFragment.CustomThemeComponentFragmentHost;
import com.android.wallpaper.R;
import com.android.wallpaper.module.InjectorProvider;
import com.android.wallpaper.module.WallpaperSetter;

import java.util.ArrayList;
import java.util.List;

public class CustomThemeActivity extends FragmentActivity implements
        CustomThemeComponentFragmentHost {
    public static final String EXTRA_THEME_TITLE = "CustomThemeActivity.ThemeTitle";
    public static final String EXTRA_THEME_PACKAGES = "CustomThemeActivity.ThemePackages";
    public static final int REQUEST_CODE_CUSTOM_THEME = 1;
    public static final int RESULT_THEME_DELETED = 10;
    public static final int RESULT_THEME_APPLIED = 20;

    private static final String TAG = "CustomThemeActivity";

    private ThemesUserEventLogger mUserEventLogger;
    private List<ComponentStep<?>> mSteps;
    private int mCurrentStep;
    private CustomThemeManager mCustomThemeManager;
    private ThemeManager mThemeManager;
    private TextView mApplyButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CustomizationInjector injector = (CustomizationInjector) InjectorProvider.getInjector();
        mUserEventLogger = (ThemesUserEventLogger) injector.getUserEventLogger(this);
        Intent intent = getIntent();
        Builder themeBuilder = null;
        if (intent != null && intent.hasExtra(EXTRA_THEME_PACKAGES)
                && intent.hasExtra(EXTRA_THEME_TITLE)) {
            ThemeBundleProvider themeProvider =
                    new DefaultThemeProvider(this, injector.getCustomizationPreferences(this));
            themeBuilder = themeProvider.parseCustomTheme(
                    intent.getStringExtra(EXTRA_THEME_PACKAGES));
            if (themeBuilder != null) {
                themeBuilder.setTitle(intent.getStringExtra(EXTRA_THEME_TITLE));
            }
        }
        mCustomThemeManager = new CustomThemeManager(themeBuilder == null ? null
                : (CustomTheme) themeBuilder.build(this));

        mThemeManager = new ThemeManager(
                new DefaultThemeProvider(this, injector.getCustomizationPreferences(this)),
                this,
                new WallpaperSetter(injector.getWallpaperPersister(this),
                        injector.getPreferences(this), mUserEventLogger, false),
                new OverlayManagerCompat(this),
                mUserEventLogger);
        mThemeManager.fetchOptions(null, false);
        setContentView(R.layout.activity_custom_theme);
        mApplyButton = findViewById(R.id.next_button);
        mApplyButton.setOnClickListener(view -> onNextOrApply());
        initSteps();

        FragmentManager fm = getSupportFragmentManager();
        Fragment fragment = fm.findFragmentById(R.id.fragment_container);
        if (fragment == null) {
            // Navigate to the first step
            navigateToStep(0);
        }
    }

    private void navigateToStep(int i) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        ComponentStep step = mSteps.get(i);
        Fragment fragment = step.getFragment();

        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        fragmentTransaction.replace(R.id.fragment_container, fragment);
        // Don't add step 0 to the back stack so that going back from it just finishes the Activity
        if (i > 0) {
            fragmentTransaction.addToBackStack("Step " + i);
        }
        fragmentTransaction.commit();
        fragmentManager.executePendingTransactions();
        updateApplyButtonLabel();
    }

    private void initSteps() {
        mSteps = new ArrayList<>();
        OverlayManagerCompat manager = new OverlayManagerCompat(this);
        mSteps.add(new FontStep(new FontOptionsProvider(this, manager), 0, 4));
        mSteps.add(new IconStep(new IconOptionsProvider(this, manager), 1, 4));
        mSteps.add(new ColorStep(new ColorOptionsProvider(this, manager, mCustomThemeManager),
                2, 4));
        mSteps.add(new ShapeStep(new ShapeOptionsProvider(this, manager), 3, 4));        
        mCurrentStep = 0;
    }

    private void onNextOrApply() {
        mCustomThemeManager.apply(getCurrentStepFragment().getSelectedOption(), new Callback() {
            @Override
            public void onSuccess() {
                if (mCurrentStep < mSteps.size() - 1) {
                    navigateToStep(mCurrentStep + 1);
                } else {
                    // We're on the last step, apply theme and leave
                    CustomTheme themeToApply = mCustomThemeManager.buildPartialCustomTheme(
                            CustomThemeActivity.this);

                    // If the current theme is equal to the original theme being edited, then
                    // don't search for an equivalent, let the user apply the same one by keeping
                    // it null.
                    ThemeBundle equivalent = (mCustomThemeManager.getOriginalTheme() != null
                            && mCustomThemeManager.getOriginalTheme().isEquivalent(themeToApply))
                                ? null : mThemeManager.findThemeByPackages(themeToApply);

                    if (equivalent != null) {
                        AlertDialog.Builder builder =
                                new AlertDialog.Builder(CustomThemeActivity.this);
                        builder.setTitle(getString(R.string.use_style_instead_title,
                                    equivalent.getTitle()))
                                .setMessage(getString(R.string.use_style_instead_body,
                                        equivalent.getTitle()))
                                .setPositiveButton(getString(R.string.use_style_button,
                                        equivalent.getTitle()),
                                        (dialogInterface, i) -> applyTheme(equivalent))
                                .setNegativeButton(R.string.no_thanks, null)
                                .create()
                                .show();
                    } else {
                        applyTheme(themeToApply);
                    }
                }
            }

            @Override
            public void onError(@Nullable Throwable throwable) {
                Log.w(TAG, "Error applying custom theme component", throwable);
                Toast.makeText(CustomThemeActivity.this, R.string.apply_theme_error_msg,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void applyTheme(ThemeBundle themeToApply) {
        mThemeManager.apply(themeToApply, new Callback() {
            @Override
            public void onSuccess() {
                Toast.makeText(CustomThemeActivity.this, R.string.applied_theme_msg,
                        Toast.LENGTH_LONG).show();
                setResult(RESULT_THEME_APPLIED);
                finish();
            }

            @Override
            public void onError(@Nullable Throwable throwable) {
                Log.w(TAG, "Error applying custom theme", throwable);
                Toast.makeText(CustomThemeActivity.this,
                        R.string.apply_theme_error_msg,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private CustomThemeComponentFragment getCurrentStepFragment() {
        return (CustomThemeComponentFragment)
                getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    }

    @Override
    public void setCurrentStep(int i) {
        mCurrentStep = i;
        updateApplyButtonLabel();
    }

    private void updateApplyButtonLabel() {
        mApplyButton.setText((mCurrentStep < mSteps.size() -1) ? R.string.custom_theme_next
                : R.string.apply_btn);
    }

    @Override
    public void delete() {
        mThemeManager.removeCustomTheme(mCustomThemeManager.getOriginalTheme());
        setResult(RESULT_THEME_DELETED);
        finish();
    }

    @Override
    public void cancel() {
        finish();
    }

    @Override
    public ThemeComponentOptionProvider<? extends ThemeComponentOption> getComponentOptionProvider(
            int position) {
        return mSteps.get(position).provider;
    }

    @Override
    public CustomThemeManager getCustomThemeManager() {
        return mCustomThemeManager;
    }

    /**
     * Represents a step in selecting a custom theme, picking a particular component (eg font,
     * color, shape, etc).
     * Each step has a Fragment instance associated that instances of this class will provide.
     */
    private static abstract class ComponentStep<T extends ThemeComponentOption> {
        @StringRes final int titleResId;
        final ThemeComponentOptionProvider<T> provider;
        final int totalSteps;
        final int position;
        private CustomThemeComponentFragment mFragment;

        protected ComponentStep(@StringRes int titleResId, ThemeComponentOptionProvider<T> provider,
                int position, int totalSteps) {
            this.titleResId = titleResId;
            this.provider = provider;
            this.position = position;
            this.totalSteps = totalSteps;
        }

        CustomThemeComponentFragment getFragment() {
            if (mFragment == null) {
                mFragment = createFragment();
            }
            return mFragment;
        }

        /**
         * @return a newly created fragment that will handle this step's UI.
         */
        abstract CustomThemeComponentFragment createFragment();
    }

    private class FontStep extends ComponentStep<FontOption> {

        protected FontStep(ThemeComponentOptionProvider<FontOption> provider,
                int position, int totalSteps) {
            super(R.string.font_component_title, provider, position, totalSteps);
        }

        @Override
        CustomThemeComponentFragment createFragment() {
            return CustomThemeComponentFragment.newInstance(
                    CustomThemeActivity.this.getString(R.string.custom_theme_fragment_title),
                    position,
                    totalSteps,
                    titleResId);
        }
    }

    private class IconStep extends ComponentStep<IconOption> {

        protected IconStep(ThemeComponentOptionProvider<IconOption> provider,
                int position, int totalSteps) {
            super(R.string.icon_component_title, provider, position, totalSteps);
        }

        @Override
        CustomThemeComponentFragment createFragment() {
            return CustomThemeComponentFragment.newInstance(
                    CustomThemeActivity.this.getString(R.string.custom_theme_fragment_title),
                    position,
                    totalSteps,
                    titleResId);
        }
    }

    private class ColorStep extends ComponentStep<ColorOption> {

        protected ColorStep(ThemeComponentOptionProvider<ColorOption> provider,
                int position, int totalSteps) {
            super(R.string.color_component_title, provider, position, totalSteps);
        }

        @Override
        CustomThemeComponentFragment createFragment() {
            return CustomThemeComponentFragment.newInstance(
                    CustomThemeActivity.this.getString(R.string.custom_theme_fragment_title),
                    position,
                    totalSteps,
                    titleResId);
        }
    }

    private class ShapeStep extends ComponentStep<ShapeOption> {

        protected ShapeStep(ThemeComponentOptionProvider<ShapeOption> provider,
                int position, int totalSteps) {
            super(R.string.shape_component_title, provider, position, totalSteps);
        }

        @Override
        CustomThemeComponentFragment createFragment() {
            return CustomThemeComponentFragment.newInstance(
                    CustomThemeActivity.this.getString(R.string.custom_theme_fragment_title),
                    position,
                    totalSteps,
                    titleResId);
        }
    }
}
