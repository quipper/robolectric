package org.robolectric.internal;

import static java.util.Arrays.asList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import javax.annotation.Nonnull;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.internal.runners.statements.FailOnTimeout;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;
import org.robolectric.internal.bytecode.ClassHandler;
import org.robolectric.internal.bytecode.ClassInstrumentor;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;
import org.robolectric.internal.bytecode.Interceptor;
import org.robolectric.internal.bytecode.Interceptors;
import org.robolectric.internal.bytecode.InvokeDynamic;
import org.robolectric.internal.bytecode.InvokeDynamicClassInstrumentor;
import org.robolectric.internal.bytecode.OldClassInstrumentor;
import org.robolectric.internal.bytecode.Sandbox;
import org.robolectric.internal.bytecode.SandboxConfig;
import org.robolectric.internal.bytecode.ShadowInfo;
import org.robolectric.internal.bytecode.ShadowMap;
import org.robolectric.internal.bytecode.ShadowWrangler;
import org.robolectric.internal.bytecode.UrlResourceProvider;
import org.robolectric.pluginapi.perf.Metadata;
import org.robolectric.pluginapi.perf.Metric;
import org.robolectric.pluginapi.perf.PerfStatsReporter;
import org.robolectric.util.PerfStatsCollector;
import org.robolectric.util.PerfStatsCollector.Event;
import org.robolectric.util.Util;
import org.robolectric.util.inject.Injector;

@SuppressWarnings("NewApi")
public class SandboxTestRunner extends BlockJUnit4ClassRunner {

  private static final ShadowMap BASE_SHADOW_MAP;
  private static final Injector DEFAULT_INJECTOR = defaultInjector().build();

  static {
    ServiceLoader<ShadowProvider> shadowProviders = ServiceLoader.load(ShadowProvider.class);
    BASE_SHADOW_MAP = ShadowMap.createFromShadowProviders(shadowProviders);
  }

  protected static Injector.Builder defaultInjector() {
    return new Injector.Builder()
        .bindDefault(ClassInstrumentor.class,
            InvokeDynamic.ENABLED
                ? InvokeDynamicClassInstrumentor.class
                : OldClassInstrumentor.class);
  }

  private final ClassInstrumentor classInstrumentor;
  private final Interceptors interceptors;
  private final List<PerfStatsReporter> perfStatsReporters;
  private final HashSet<Class<?>> loadedTestClasses = new HashSet<>();

  public SandboxTestRunner(Class<?> klass) throws InitializationError {
    this(klass, DEFAULT_INJECTOR);
  }

  public SandboxTestRunner(Class<?> klass, Injector injector) throws InitializationError {
    super(klass);

    classInstrumentor = injector.getInstance(ClassInstrumentor.class);
    interceptors = new Interceptors(findInterceptors());

    perfStatsReporters = Arrays.asList(injector.getInstance(PerfStatsReporter[].class));
  }

  @Nonnull
  protected Collection<Interceptor> findInterceptors() {
    return Collections.emptyList();
  }

  @Nonnull
  protected Interceptors getInterceptors() {
    return interceptors;
  }

  @Override
  protected Statement classBlock(RunNotifier notifier) {
    final Statement statement = childrenInvoker(notifier);
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        try {
          statement.evaluate();
          for (Class<?> testClass : loadedTestClasses) {
            invokeAfterClass(testClass);
          }
        } finally {
          afterClass();
          loadedTestClasses.clear();
        }
      }
    };
  }

  private void invokeBeforeClass(final Class clazz) throws Throwable {
    if (!loadedTestClasses.contains(clazz)) {
      loadedTestClasses.add(clazz);

      final TestClass testClass = new TestClass(clazz);
      final List<FrameworkMethod> befores = testClass.getAnnotatedMethods(BeforeClass.class);
      for (FrameworkMethod before : befores) {
        before.invokeExplosively(null);
      }
    }
  }

  private static void invokeAfterClass(final Class<?> clazz) throws Throwable {
    final TestClass testClass = new TestClass(clazz);
    final List<FrameworkMethod> afters = testClass.getAnnotatedMethods(AfterClass.class);
    for (FrameworkMethod after : afters) {
      after.invokeExplosively(null);
    }
  }

  protected void afterClass() {
  }

  @Nonnull
  protected Sandbox getSandbox(FrameworkMethod method) {
    InstrumentationConfiguration instrumentationConfiguration = createClassLoaderConfig(method);
    return new Sandbox(instrumentationConfiguration, new UrlResourceProvider(), classInstrumentor);
  }

  /**
   * Create an {@link InstrumentationConfiguration} suitable for the provided {@link FrameworkMethod}.
   *
   * Custom TestRunner subclasses may wish to override this method to provide alternate configuration.
   *
   * @param method the test method that's about to run
   * @return an {@link InstrumentationConfiguration}
   */
  @Nonnull
  protected InstrumentationConfiguration createClassLoaderConfig(FrameworkMethod method) {
    InstrumentationConfiguration.Builder builder = InstrumentationConfiguration.newBuilder()
        .doNotAcquirePackage("java.")
        .doNotAcquirePackage("sun.")
        .doNotAcquirePackage("org.robolectric.annotation.")
        .doNotAcquirePackage("org.robolectric.internal.")
        .doNotAcquirePackage("org.robolectric.pluginapi.")
        .doNotAcquirePackage("org.robolectric.util.")
        .doNotAcquirePackage("org.junit.");

    String customPackages = System.getProperty("org.robolectric.packagesToNotAcquire", "");
    for (String pkg : customPackages.split(",")) {
      if (!pkg.isEmpty()) {
        builder.doNotAcquirePackage(pkg);
      }
    }

    for (Class<?> shadowClass : getExtraShadows(method)) {
      ShadowInfo shadowInfo = ShadowMap.obtainShadowInfo(shadowClass);
      builder.addInstrumentedClass(shadowInfo.shadowedClassName);
    }

    addInstrumentedPackages(method, builder);

    return builder.build();
  }

  private void addInstrumentedPackages(FrameworkMethod method, InstrumentationConfiguration.Builder builder) {
    SandboxConfig classConfig = getTestClass().getJavaClass().getAnnotation(SandboxConfig.class);
    if (classConfig != null) {
      for (String pkgName : classConfig.instrumentedPackages()) {
        builder.addInstrumentedPackage(pkgName);
      }
    }

    SandboxConfig methodConfig = method.getAnnotation(SandboxConfig.class);
    if (methodConfig != null) {
      for (String pkgName : methodConfig.instrumentedPackages()) {
        builder.addInstrumentedPackage(pkgName);
      }
    }
  }

  protected void configureSandbox(Sandbox sandbox, FrameworkMethod method) {
    ShadowMap.Builder builder = createShadowMap().newBuilder();

    // Configure shadows *BEFORE* setting the ClassLoader. This is necessary because
    // creating the ShadowMap loads all ShadowProviders via ServiceLoader and this is
    // not available once we install the Robolectric class loader.
    Class<?>[] shadows = getExtraShadows(method);
    if (shadows.length > 0) {
      builder.addShadowClasses(shadows);
    }
    ShadowMap shadowMap = builder.build();
    sandbox.replaceShadowMap(shadowMap);

    sandbox.configure(createClassHandler(shadowMap, sandbox), getInterceptors());
  }

  @Override protected Statement methodBlock(final FrameworkMethod method) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        PerfStatsCollector perfStatsCollector = PerfStatsCollector.getInstance();
        perfStatsCollector.reset();
        perfStatsCollector.setEnabled(!perfStatsReporters.isEmpty());

        Event initialization = perfStatsCollector.startEvent("initialization");

        Sandbox sandbox = getSandbox(method);

        // Configure sandbox *BEFORE* setting the ClassLoader. This is necessary because
        // creating the ShadowMap loads all ShadowProviders via ServiceLoader and this is
        // not available once we install the Robolectric class loader.
        configureSandbox(sandbox, method);

        sandbox.runOnMainThread(() -> {
          ClassLoader priorContextClassLoader = Thread.currentThread().getContextClassLoader();
          Thread.currentThread().setContextClassLoader(sandbox.getRobolectricClassLoader());

          Class bootstrappedTestClass =
              sandbox.bootstrappedClass(getTestClass().getJavaClass());
          HelperTestRunner helperTestRunner = getHelperTestRunner(bootstrappedTestClass);
          helperTestRunner.frameworkMethod = method;

          final Method bootstrappedMethod;
          try {
            //noinspection unchecked
            bootstrappedMethod = bootstrappedTestClass.getMethod(method.getMethod().getName());
          } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
          }

          try {
            // Only invoke @BeforeClass once per class
            invokeBeforeClass(bootstrappedTestClass);

            beforeTest(sandbox, method, bootstrappedMethod);

            initialization.finished();

            Statement statement =
                helperTestRunner.methodBlock(new FrameworkMethod(bootstrappedMethod));

            // todo: this try/finally probably isn't right -- should mimic RunAfters? [xw]
            try {
              statement.evaluate();
            } finally {
              afterTest(method, bootstrappedMethod);
            }
          } catch (Throwable throwable) {
            Util.sneakyThrow(throwable);
          } finally {
            Thread.currentThread().setContextClassLoader(priorContextClassLoader);
            try {
              finallyAfterTest(method);
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        });

        reportPerfStats(perfStatsCollector);
        perfStatsCollector.reset();
      }
    };
  }

  private void reportPerfStats(PerfStatsCollector perfStatsCollector) {
    if (perfStatsReporters.isEmpty()) {
      return;
    }

    Metadata metadata = perfStatsCollector.getMetadata();
    Collection<Metric> metrics = perfStatsCollector.getMetrics();

    for (PerfStatsReporter perfStatsReporter : perfStatsReporters) {
      try {
        perfStatsReporter.report(metadata, metrics);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  protected void beforeTest(Sandbox sandbox, FrameworkMethod method, Method bootstrappedMethod) throws Throwable {
  }

  protected void afterTest(FrameworkMethod method, Method bootstrappedMethod) {
  }

  protected void finallyAfterTest(FrameworkMethod method) {
  }

  protected HelperTestRunner getHelperTestRunner(Class bootstrappedTestClass) {
    try {
      return new HelperTestRunner(bootstrappedTestClass);
    } catch (InitializationError initializationError) {
      throw new RuntimeException(initializationError);
    }
  }

  protected static class HelperTestRunner extends BlockJUnit4ClassRunner {
    public FrameworkMethod frameworkMethod;

    public HelperTestRunner(Class<?> klass) throws InitializationError {
      super(klass);
    }

    // for visibility from SandboxTestRunner.methodBlock()
    @Override
    protected Statement methodBlock(FrameworkMethod method) {
      return super.methodBlock(method);
    }

    /**
     * For tests with a timeout, we need to wrap the test method execution (but not `@Before`s or
     * `@After`s) in a {@link TimeLimitedStatement}. JUnit's built-in {@link FailOnTimeout}
     * statement causes the test method (but not `@Before`s or `@After`s) to be run on a short-lived
     * thread. This is inadequate for our purposes; we want to guarantee that every entry point to
     * test code is run from the same thread.
     */
    @Override
    protected Statement methodInvoker(FrameworkMethod method, Object test) {
      Statement delegate = super.methodInvoker(method, test);
      long timeout = getTimeout(method.getAnnotation(Test.class));

      if (timeout == 0) {
        return delegate;
      } else {
        return new TimeLimitedStatement(timeout, delegate);
      }
    }

    /**
     * Disables JUnit's normal timeout mode strategy.
     *
     * @see #methodInvoker(FrameworkMethod, Object)
     * @see TimeLimitedStatement
     */
    @Override
    protected Statement withPotentialTimeout(FrameworkMethod method, Object test, Statement next) {
      return next;
    }

    private long getTimeout(Test annotation) {
      if (annotation == null) {
        return 0;
      }
      return annotation.timeout();
    }

  }

  @Nonnull
  protected Class<?>[] getExtraShadows(FrameworkMethod method) {
    List<Class<?>> shadowClasses = new ArrayList<>();
    addShadows(shadowClasses, getTestClass().getJavaClass().getAnnotation(SandboxConfig.class));
    addShadows(shadowClasses, method.getAnnotation(SandboxConfig.class));
    return shadowClasses.toArray(new Class[shadowClasses.size()]);
  }

  private void addShadows(List<Class<?>> shadowClasses, SandboxConfig annotation) {
    if (annotation != null) {
      shadowClasses.addAll(asList(annotation.shadows()));
    }
  }

  protected ShadowMap createShadowMap() {
    return BASE_SHADOW_MAP;
  }

  @Nonnull
  protected ClassHandler createClassHandler(ShadowMap shadowMap, Sandbox sandbox) {
    return new ShadowWrangler(shadowMap, 0, interceptors);
  }

  /**
   * Disables JUnit's normal timeout mode strategy.
   *
   * @see #methodInvoker(FrameworkMethod, Object)
   * @see TimeLimitedStatement
   */
  protected Statement withPotentialTimeout(FrameworkMethod method, Object test, Statement next) {
    return next;
  }
}