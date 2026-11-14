/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.litho.testing.testrunner;

import androidx.annotation.Nullable;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.litho.ComponentsSystrace;
import com.facebook.litho.config.ComponentsConfiguration;
import com.facebook.rendercore.MountContentPools;
import com.google.common.base.Preconditions;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.robolectric.DefaultTestLifecycle;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.TestLifecycle;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;
import org.robolectric.internal.bytecode.Sandbox;
import org.robolectric.util.ReflectionHelpers;

@Nullsafe(Nullsafe.Mode.LOCAL)
public class LithoTestRunner extends RobolectricTestRunner {

  private final List<Class<? extends LithoLocalTestRunConfiguration>> mLocalTestRunConfigs;

  /**
   * Creates a runner to run {@code testClass}. Looks in your working directory for your
   * AndroidManifest.xml file and res directory by default. Use the {@link Config} annotation to
   * configure.
   *
   * @param testClass the test class to be run
   * @throws InitializationError if junit says so
   */
  @SuppressWarnings("this-escape")
  public LithoTestRunner(final Class<?> testClass) throws InitializationError {
    super(testClass);
    @Nullable
    LocalConfigurations annotation = getTestClass().getAnnotation(LocalConfigurations.class);
    if (annotation != null) {
      mLocalTestRunConfigs = Arrays.asList(annotation.value());
    } else {
      mLocalTestRunConfigs = Collections.emptyList();
    }
  }

  /**
   * @return a list of LithoTestRunConfiguration that the test suite should also be run with. The
   *     test suite will always be run without any extra configurations in addition to these run
   *     configurations.
   */
  private List<? extends Class<? extends LithoTestRunConfiguration>> getGlobalConfigs() {
    return Arrays.asList(
        IncrementalMountConfiguration.class,
        CachedValueFromStateConfiguration.class,
        MemoryEfficientGlobalKeysConfiguration.class);
  }

  /**
   * Exclude packages that load native libraries from Robolectric sandbox instrumentation.
   *
   * <p>Native libraries are pinned to the classloader that first loads them. When LithoTestRunner
   * multiplies tests across configurations, Robolectric may create different sandbox classloaders.
   * Without this exclusion, the second sandbox fails with "UnsatisfiedLinkError: Native Library
   * libyoga.so already loaded in another classloader".
   *
   * <p>By excluding these packages, the classes are loaded by the parent (app) classloader instead,
   * which is shared across all sandboxes. This matches the pattern used by WithTestDefaultsRunner.
   */
  @Override
  protected InstrumentationConfiguration createClassLoaderConfig(FrameworkMethod method) {
    InstrumentationConfiguration.Builder builder =
        new InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method));
    builder.doNotAcquirePackage("com.facebook.yoga.");
    builder.doNotAcquirePackage("com.facebook.jni.");
    builder.doNotAcquirePackage("com.facebook.soloader.nativeloader.");
    return builder.build();
  }

  @Override
  protected List<FrameworkMethod> getChildren() {
    // NULLSAFE_FIXME[Not Vetted Third-Party]
    final List<FrameworkMethod> children = super.getChildren();

    final List<Class<? extends LithoTestRunConfiguration>> configs = new ArrayList<>();
    configs.addAll(getGlobalConfigs());

    if (configs.isEmpty()) {
      return children;
    }

    // Add instances of the method to run for each extra run configuration we have
    final ArrayList<FrameworkMethod> res = new ArrayList<>();
    for (FrameworkMethod method : children) {
      res.add(method);
      for (Class<? extends LithoLocalTestRunConfiguration> localConfig : mLocalTestRunConfigs) {
        res.add(
            new LithoRobolectricFrameworkMethod(
                (RobolectricFrameworkMethod) method, null, localConfig));
      }

      for (Class<? extends LithoTestRunConfiguration> configuration : configs) {
        res.add(
            new LithoRobolectricFrameworkMethod(
                (RobolectricFrameworkMethod) method, configuration, null));
        for (Class<? extends LithoLocalTestRunConfiguration> localConfig : mLocalTestRunConfigs) {
          res.add(
              new LithoRobolectricFrameworkMethod(
                  (RobolectricFrameworkMethod) method, configuration, localConfig));
        }
      }
    }

    return res;
  }

  @SuppressWarnings("CatchGeneralException")
  @Override
  protected void beforeTest(Sandbox sandbox, FrameworkMethod method, Method bootstrappedMethod)
      throws Throwable {
    super.beforeTest(sandbox, method, bootstrappedMethod);

    // The reason we accept LithoTestRunConfigurations as classes is because we need to load them in
    // the Sandbox class loader - otherwise they will only be able to modify the
    // ComponentsConfiguration loaded in the 'app' classloader, meaning changes to it won't be seen
    // within tests.
    if (method instanceof LithoRobolectricFrameworkMethod) {
      LithoRobolectricFrameworkMethod lithoMethod = (LithoRobolectricFrameworkMethod) method;
      lithoMethod.sandbox = sandbox;

      if (lithoMethod.configurationClass != null) {
        lithoMethod.configurationInstance =
            ReflectionHelpers.newInstance(
                sandbox.<LithoTestRunConfiguration>bootstrappedClass(
                    lithoMethod.configurationClass));

        // This hack is because LithoTestRunConfiguration is loaded in both the main 'app'
        // classloader and in the sandbox classloader the test runs with. Normally, we would exclude
        // LithoTestRunConfiguration by overriding createClassLoaderConfig and in this class using
        // doNotAcquireClass, however doing leads to the dreaded "Cannot load NodeConfig" error due
        // to the change in classloader config, thus we hack around it with reflection instead.
        Class<LithoTestRunner> testConfig =
            // NULLSAFE_FIXME[Not Vetted Third-Party]
            lithoMethod.sandbox.bootstrappedClass(LithoTestRunConfiguration.class);
        try {
          testConfig
              .getMethod("beforeTest", FrameworkMethod.class)
              .invoke(lithoMethod.configurationInstance, method);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }

      if (lithoMethod.localConfigurationClass != null) {
        lithoMethod.localConfigurationInstance =
            ReflectionHelpers.newInstance(
                sandbox.<LithoTestRunConfiguration>bootstrappedClass(
                    lithoMethod.localConfigurationClass));

        // This hack is because LithoLocalTestRunConfiguration is loaded in both the main 'app'
        // classloader and in the sandbox classloader the test runs with. Normally, we would exclude
        // LithoLocalTestRunConfiguration by overriding createClassLoaderConfig and in this class
        // using doNotAcquireClass, however doing leads to the dreaded "Cannot load NodeConfig"
        // error due to the change in classloader config, thus we hack around it with reflection
        // instead.
        Class<LithoTestRunner> localTestConfig =
            // NULLSAFE_FIXME[Not Vetted Third-Party]
            lithoMethod.sandbox.bootstrappedClass(LithoLocalTestRunConfiguration.class);
        try {
          localTestConfig
              .getMethod("beforeTest", FrameworkMethod.class)
              .invoke(lithoMethod.localConfigurationInstance, method);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  @SuppressWarnings("CatchGeneralException")
  @Override
  protected void afterTest(FrameworkMethod method, Method bootstrappedMethod) {
    if (method instanceof LithoRobolectricFrameworkMethod) {
      LithoRobolectricFrameworkMethod lithoMethod = (LithoRobolectricFrameworkMethod) method;

      // See comment in beforeTest above
      if (lithoMethod.configurationInstance != null) {
        Class<LithoTestRunner> testConfig =
            Preconditions.checkNotNull(lithoMethod.sandbox)
                // NULLSAFE_FIXME[Not Vetted Third-Party]
                .bootstrappedClass(LithoTestRunConfiguration.class);
        try {
          testConfig
              .getMethod("afterTest", FrameworkMethod.class)
              .invoke(lithoMethod.configurationInstance, method);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      lithoMethod.configurationInstance = null;

      if (lithoMethod.localConfigurationInstance != null) {
        Class<LithoTestRunner> localTestConfig =
            Preconditions.checkNotNull(lithoMethod.sandbox)
                // NULLSAFE_FIXME[Not Vetted Third-Party]
                .bootstrappedClass(LithoLocalTestRunConfiguration.class);
        try {
          localTestConfig
              .getMethod("afterTest", FrameworkMethod.class)
              .invoke(lithoMethod.localConfigurationInstance, method);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      lithoMethod.localConfigurationInstance = null;

      lithoMethod.sandbox = null;
    }
    super.afterTest(method, bootstrappedMethod);
  }

  @Override
  protected Class<? extends TestLifecycle> getTestLifecycleClass() {
    return LithoTestLifecycle.class;
  }

  private static class LithoRobolectricFrameworkMethod extends RobolectricFrameworkMethod {

    public final @Nullable Class<? extends LithoTestRunConfiguration> configurationClass;
    public final @Nullable Class<? extends LithoLocalTestRunConfiguration> localConfigurationClass;

    // This is meant to be a LithoTestRunConfiguration instance - it isn't because the
    // LithoTestRunConfiguration that will be the parent class of this instance is from a different
    // class loader than LithoRobolectricFrameworkMethod will be loaded in.
    @Nullable Object configurationInstance;
    @Nullable Object localConfigurationInstance;
    RobolectricFrameworkMethod baseMethod;
    @Nullable Sandbox sandbox;

    protected LithoRobolectricFrameworkMethod(
        RobolectricFrameworkMethod other,
        @Nullable Class<? extends LithoTestRunConfiguration> configurationClass,
        @Nullable Class<? extends LithoLocalTestRunConfiguration> localConfigClass) {
      super(other);
      this.configurationClass = configurationClass;
      this.localConfigurationClass = localConfigClass;
      this.baseMethod = other;
    }

    @SuppressWarnings("ReflectionMethodUse")
    @Override
    public String getName() {
      // NULLSAFE_FIXME[Not Vetted Third-Party]
      StringBuilder name = new StringBuilder(super.getName());

      String variant =
          (configurationClass != null ? configurationClass.getSimpleName() + ":" : "")
              + (localConfigurationClass != null ? localConfigurationClass.getSimpleName() : "");
      name.append("[");
      name.append(variant);
      name.append("]");

      int apiLevel = baseMethod.getSdk().getApiLevel();
      name.append("[api=");
      name.append(apiLevel);
      name.append("]");

      LooperMode.Mode looperMode = baseMethod.getConfiguration().get(LooperMode.Mode.class);
      name.append("[looperMode=");
      name.append(looperMode);
      name.append("]");

      return name.toString();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other == null || getClass() != other.getClass() || !super.equals(other)) {
        return false;
      }
      LithoRobolectricFrameworkMethod that = (LithoRobolectricFrameworkMethod) other;
      return Objects.equals(configurationClass, that.configurationClass)
          && Objects.equals(localConfigurationClass, that.localConfigurationClass);
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), configurationClass, localConfigurationClass);
    }
  }

  public static class LithoTestLifecycle extends DefaultTestLifecycle {

    private final Map<Field, Object> mSavedComponentsConfiguration;

    public LithoTestLifecycle() {
      ComponentsSystrace.provide(NoOpComponentsSystrace.sInstance);
      mSavedComponentsConfiguration = recordComponentsConfiguration();
    }

    @Override
    public void beforeTest(Method method) {
      super.beforeTest(method);
      // MountContentPools is process-global, so a test that mounts content without LithoTestRule
      // leaves both pooled content and any installed Factory behind. Reset here so a test is
      // isolated from whatever ran before it, rather than relying on the polluter to clean up.
      MountContentPools.setMountContentPoolFactory(null);
      MountContentPools.clear();
    }

    @Override
    public void afterTest(Method method) {
      super.afterTest(method);
      restoreComponentsConfiguration(mSavedComponentsConfiguration);
    }

    private static Map<Field, Object> recordComponentsConfiguration() {
      final HashMap<Field, Object> record = new HashMap<>();
      for (Field f : ComponentsConfiguration.class.getDeclaredFields()) {
        final int modifiers = f.getModifiers();
        if (Modifier.isStatic(modifiers)
            && Modifier.isPublic(modifiers)
            && !Modifier.isFinal(modifiers)) {
          try {
            record.put(f, f.get(null));
          } catch (IllegalAccessException e) {
            throw new RuntimeException("Couldn't record ComponentsConfiguration state", e);
          }
        }
      }
      return record;
    }

    private static void restoreComponentsConfiguration(Map<Field, Object> fieldsToValues) {
      for (Map.Entry<Field, Object> entry : fieldsToValues.entrySet()) {
        try {
          entry.getKey().set(null, entry.getValue());
        } catch (IllegalAccessException e) {
          throw new RuntimeException("Couldn't restore ComponentsConfiguration state", e);
        }
      }
    }
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  public @interface LocalConfigurations {
    Class<? extends LithoLocalTestRunConfiguration>[] value();
  }
}
