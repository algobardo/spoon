package com.squareup.spoon;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.squareup.spoon.html.HtmlRenderer;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.io.FileUtils;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.squareup.spoon.DeviceTestResult.Status;
import static com.squareup.spoon.SpoonInstrumentationInfo.parseFromFile;
import static com.squareup.spoon.SpoonLogger.logDebug;
import static com.squareup.spoon.SpoonLogger.logInfo;
import static java.util.Collections.synchronizedSet;

/** Represents a collection of devices and the test configuration to be executed. */
public final class SpoonRunner {
  private static final String DEFAULT_TITLE = "Spoon Execution";
  public static final String DEFAULT_OUTPUT_DIRECTORY = "spoon-output";
  private static final int DEFAULT_ADB_TIMEOUT = 10 * 60; //10 minutes

  private final String title;
  private final File androidSdk;
  private final File applicationApk;
  private final File instrumentationApk;
  private final File output;
  private final boolean debug;
  private final boolean noAnimations;
  private final int adbTimeout;
  private final String subpackageName;
  private final String className;
  private final String methodName;
  private final boolean noInstall;
  private List<String> filterTags = null;
  private final Set<String> serials;
  private final String classpath;
  private final String runId;
  private final IRemoteAndroidTestRunner.TestSize testSize;
  private final boolean failIfNoDeviceConnected;

  private SpoonRunner(String title, File androidSdk, File applicationApk, File instrumentationApk,
      File output, boolean debug, boolean noAnimations, int adbTimeout, Set<String> serials,
      String classpath, String subpackageName, String className, String methodName,
      IRemoteAndroidTestRunner.TestSize testSize, boolean noInstall, boolean failIfNoDeviceConnected,
      String filterLog, String runId) {
    this.title = title;
    this.androidSdk = androidSdk;
    this.applicationApk = applicationApk;
    this.instrumentationApk = instrumentationApk;
    this.output = output;
    this.debug = debug;
    this.noAnimations = noAnimations;
    this.adbTimeout = adbTimeout;
    this.subpackageName = subpackageName;
    this.className = className;
    this.methodName = methodName;
    this.classpath = classpath;
    this.testSize = testSize;
    this.noInstall = noInstall;
    this.runId = runId;
    this.serials = ImmutableSet.copyOf(serials);
    this.failIfNoDeviceConnected = failIfNoDeviceConnected;

    if(filterLog != null){
      this.filterTags = new LinkedList();
      this.filterTags.addAll(Arrays.asList(filterLog.split("&")));
    }
    
  }

  /**
   * Install and execute the tests on all specified devices.
   *
   * @return {@code true} if there were no test failures or exceptions thrown.
   */
  public boolean run(CommandLineArgs parsedArgs) {
    checkArgument(applicationApk.exists(), "Could not find application APK.");
    checkArgument(instrumentationApk.exists(), "Could not find instrumentation APK.");

    AndroidDebugBridge adb = SpoonUtils.initAdb(androidSdk);

    try {
      // If we were given an empty serial set, load all available devices.
      Set<String> serials = this.serials;
      if (serials.isEmpty()) {
        serials = SpoonUtils.findAllDevices(adb);
      }
      if (failIfNoDeviceConnected && serials.isEmpty()) {
        throw new RuntimeException("No device(s) found.");
      }

      // Execute all the things...
      SpoonSummary summary = runTests(adb, serials);
      // ...and render to HTML
      new HtmlRenderer(summary, SpoonUtils.GSON, output, parsedArgs.noresultjson, parsedArgs.nohtml).render();
      return parseOverallSuccess(summary);
    } finally {
    	logDebug(true,"HHERE3");
      AndroidDebugBridge.terminate();
    }
  }

  private SpoonSummary runTests(AndroidDebugBridge adb, Set<String> serials) {
    int targetCount = serials.size();
    logInfo("Executing instrumentation suite on %d device(s).", targetCount);

    try {
      FileUtils.deleteDirectory(output);
    } catch (IOException e) {
      throw new RuntimeException("Unable to clean output directory: " + output, e);
    }

    final SpoonInstrumentationInfo testInfo = parseFromFile(instrumentationApk);
    logDebug(debug, "Application: %s from %s", testInfo.getApplicationPackage(),
        applicationApk.getAbsolutePath());
    logDebug(debug, "Instrumentation: %s from %s", testInfo.getInstrumentationPackage(),
        instrumentationApk.getAbsolutePath());

    final SpoonSummary.Builder summary = new SpoonSummary.Builder().setTitle(title).start();

    if (testSize != null) {
      summary.setTestSize(testSize);
    }

    if (targetCount == 1) {
      // Since there is only one device just execute it synchronously in this process.
      String serial = serials.iterator().next();
      String safeSerial = SpoonUtils.sanitizeSerial(serial);
      try {
        logDebug(debug, "[%s] Starting execution.", serial);
        summary.addResult(safeSerial, getTestRunner(serial, testInfo).run(adb));
      } catch (Exception e) {
        logDebug(debug, "[%s] Execution exception!", serial);
        e.printStackTrace(System.out);
        summary.addResult(safeSerial, new DeviceResult.Builder().addException(e).build());
      } finally {
        logDebug(debug, "[%s] Execution done.", serial);
      }
    } else {
      // Spawn a new thread for each device and wait for them all to finish.
      final CountDownLatch done = new CountDownLatch(targetCount);
      final Set<String> remaining = synchronizedSet(new HashSet<String>(serials));
      for (final String serial : serials) {
        final String safeSerial = SpoonUtils.sanitizeSerial(serial);
        logDebug(debug, "[%s] Starting execution.", serial);
        new Thread(new Runnable() {
          @Override public void run() {
            try {
              summary.addResult(safeSerial, getTestRunner(serial, testInfo).runInNewProcess());
            } catch (Exception e) {
              summary.addResult(safeSerial, new DeviceResult.Builder().addException(e).build());
            } finally {
              done.countDown();
              remaining.remove(serial);
              logDebug(debug, "[%s] Execution done. (%s remaining %s)", serial, done.getCount(),
                  remaining);
            }
          }
        }).start();
      }

      try {
        done.await();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    if (!debug) {
      // Clean up anything in the work directory.
      try {
        FileUtils.deleteDirectory(new File(output, SpoonDeviceRunner.TEMP_DIR));
      } catch (IOException ignored) {
      }
    }

    return summary.end().build();
  }

  /** Returns {@code false} if a test failed on any device. */
  static boolean parseOverallSuccess(SpoonSummary summary) {
    for (DeviceResult result : summary.getResults().values()) {
      if (result.getInstallFailed()) {
        return false; // App and/or test installation failed.
      }
      if (!result.getExceptions().isEmpty() && result.getTestResults().isEmpty()) {
        return false; // No tests run and top-level exception present.
      }
      for (DeviceTestResult methodResult : result.getTestResults().values()) {
        if (methodResult.getOverallStatus() != Status.PASS) {
          return false; // Individual test failure.
        }
      }
    }
    logDebug(true,"parseOverallSuccess returns");
    return true;
  }

  private SpoonDeviceRunner getTestRunner(String serial, SpoonInstrumentationInfo testInfo) {
    return new SpoonDeviceRunner(androidSdk, applicationApk, instrumentationApk, output, serial,
        debug, noAnimations, adbTimeout, classpath, testInfo, subpackageName, className, methodName, noInstall, 
        filterTags, testSize, runId);
  }

  /** Build a test suite for the specified devices and configuration. */
  public static class Builder {
    private String title = DEFAULT_TITLE;
    private File androidSdk;
    private File applicationApk;
    private File instrumentationApk;
    private File output;
    private boolean debug = false;
    private Set<String> serials;
    private String classpath = System.getProperty("java.class.path");
    private String subpackageName;
    private String className;
    private String methodName;
    private String runId;
    private boolean noAnimations;
    private boolean noInstall;
    private String filterLog;
    private IRemoteAndroidTestRunner.TestSize testSize;
    private int adbTimeout;
    private boolean failIfNoDeviceConnected;

    /** Identifying title for this execution. */
    public Builder setTitle(String title) {
      checkNotNull(title, "Title cannot be null.");
      this.title = title;
      return this;
    }

    /** Path to the local Android SDK directory. */
    public Builder setAndroidSdk(File androidSdk) {
      checkNotNull(androidSdk, "SDK path not specified.");
      checkArgument(androidSdk.exists(), "SDK path does not exist.");
      this.androidSdk = androidSdk;
      return this;
    }

    /** Path to application APK. */
    public Builder setApplicationApk(File apk) {
      checkNotNull(apk, "APK path not specified.");
      checkArgument(apk.exists(), "APK path does not exist.");
      this.applicationApk = apk;
      return this;
    }

    /** Path to instrumentation APK. */
    public Builder setInstrumentationApk(File apk) {
      checkNotNull(apk, "Instrumentation APK path not specified.");
      checkArgument(apk.exists(), "Instrumentation APK path does not exist.");
      this.instrumentationApk = apk;
      return this;
    }

    /** Path to output directory. */
    public Builder setOutputDirectory(File output) {
      checkNotNull(output, "Output directory not specified.");
      this.output = output;
      return this;
    }

    /** Whether or not debug logging is enabled. */
    public Builder setDebug(boolean debug) {
      this.debug = debug;
      return this;
    }

    /** Whether or not install the application */
    public Builder setNoInstall(boolean noInstall) {
      this.noInstall = noInstall;
      return this;
    }

    /** Set the id of this spoon run. */
    public Builder setRunId(String runId) {
      this.runId = runId;
      return this;
    }

    /** Whether or not animations are enabled. */
    public Builder setNoAnimations(boolean noAnimations) {
      this.noAnimations = noAnimations;
      return this;
    }

    /** Set ADB timeout. */
    public Builder setAdbTimeout(int value) {
      this.adbTimeout = value;
      return this;
    }

    /** Add a device serial for test execution. */
    public Builder addDevice(String serial) {
      checkNotNull(serial, "Serial cannot be null.");
      checkArgument(serials == null || !serials.isEmpty(), "Already marked as using all devices.");
      if (serials == null) {
        serials = new LinkedHashSet<String>();
      }
      serials.add(serial);
      return this;
    }

    /** Use all currently attached device serials when executed. */
    public Builder useAllAttachedDevices() {
      if (this.serials != null) {
        throw new IllegalStateException("Serial list already contains entries.");
      }
      if (this.androidSdk == null) {
        throw new IllegalStateException("SDK must be set before calling this method.");
      }
      this.serials = Collections.emptySet();
      return this;
    }

    /** Classpath to use for new JVM processes. */
    public Builder setClasspath(String classpath) {
      checkNotNull(classpath, "Classpath cannot be null.");
      this.classpath = classpath;
      return this;
    }

    public Builder setSubpackageName(String subpackageName) {
      this.subpackageName = subpackageName;
      return this;
    }

    public Builder setClassName(String className) {
      this.className = className;
      return this;
    }

    public Builder setTestSize(IRemoteAndroidTestRunner.TestSize testSize) {
      this.testSize = testSize;
      return this;
    }

    public Builder setFailIfNoDeviceConnected(boolean failIfNoDeviceConnected) {
      this.failIfNoDeviceConnected = failIfNoDeviceConnected;
      return this;
    }

    public Builder setMethodName(String methodName) {
      this.methodName = methodName;
      return this;
    }

    public Builder setFilterLog(String filterLog) {
      this.filterLog = filterLog;
      return this;
    }

    public SpoonRunner build() {
      checkNotNull(androidSdk, "SDK is required.");
      checkArgument(androidSdk.exists(), "SDK path does not exist.");
      checkNotNull(applicationApk, "Application APK is required.");
      checkNotNull(instrumentationApk, "Instrumentation APK is required.");
      checkNotNull(output, "Output path is required.");
      checkNotNull(serials, "Device serials are required.");
      if (!Strings.isNullOrEmpty(methodName)) {
        checkArgument(!Strings.isNullOrEmpty(className),
            "Must specify class name if you're specifying a method name.");
      }

      return new SpoonRunner(title, androidSdk, applicationApk, instrumentationApk, output, debug,
          noAnimations, adbTimeout, serials, classpath, subpackageName, className, methodName, testSize,
          noInstall, failIfNoDeviceConnected, filterLog, runId);
    }
  }

  static class CommandLineArgs {
    @Parameter(names = { "--title" }, description = "Execution title")
    public String title = DEFAULT_TITLE;

    @Parameter(names = { "--apk" }, description = "Application APK",
        converter = FileConverter.class)
    public File apk;

    @Parameter(names = { "--test-apk" }, description = "Test application APK",
        converter = FileConverter.class)
    public File testApk;

    @Parameter(names = { "--subpackage-name" }, description = "Sub-package to run (fully-qualified)")
    public String subpackageName;
    
    @Parameter(names = { "--class-name" }, description = "Test class name to run (fully-qualified)")
    public String className;

    @Parameter(names = { "--method-name" }, description =
        "Test method name to run (must also use --class-name)")
    public String methodName;

    @Parameter(names = { "--size" }, converter = TestSizeConverter.class,
        description = "Only run methods with corresponding size annotation (small, medium, large)")
    public IRemoteAndroidTestRunner.TestSize size;

    @Parameter(names = { "--output" }, description = "Output path",
        converter = FileConverter.class)
    public File output = cleanFile(SpoonRunner.DEFAULT_OUTPUT_DIRECTORY);

    @Parameter(names = { "--sdk" }, description = "Path to Android SDK")
    public File sdk = cleanFile(System.getenv("ANDROID_HOME"));

    @Parameter(names = { "--fail-on-failure" }, description = "Non-zero exit code on failure")
    public boolean failOnFailure;

    @Parameter(names = { "--fail-if-no-device-connected" },
         description = "Fail if no device is connected")
    public boolean failIfNoDeviceConnected;

    @Parameter(names = { "--no-animations" }, description = "Disable animated gif generation")
    public boolean noAnimations;

    @Parameter(names = { "--adb-timeout" },
        description = "Set maximum execution time per test in seconds (10min default)")
    public int adbTimeoutSeconds = DEFAULT_ADB_TIMEOUT;

    @Parameter(names = { "--debug" }, hidden = true)
    public boolean debug;

    @Parameter(names = { "-h", "--help" }, description = "Command help", help = true, hidden = true)
    public boolean help;

    @Parameter(names = { "--noprettify" },
            description = "Disable pretty method name", help = true, hidden = false)
    public boolean nopretty;

    @Parameter(names = { "--aggregate" }, description =
              "Run in aggregation mode, aggregating previous results", help = true)
    public List<String> aggregate;

    @Parameter(names = { "--aggregateout" }, description =
              "The directory where to put the aggregation result", help = true)
    public String aggregate_out;

    @Parameter(names = { "--noinstall" }, description =
              "Avoid installing the app", help = true)
    public boolean noinstall;

    @Parameter(names = { "--noresultjson" }, description =
              "Avoid writing result.json", help = true)
    public boolean noresultjson;

    @Parameter(names = { "--nohtml" }, description =
              "Avoid writing html pages and resources", help = true)
    public boolean nohtml;

    @Parameter(names = { "--filterLog" }, description =
              "\"&\" separated list of log tag to keep ", help = true)
    public String filterLog;
    
    @Parameter(names = { "--runId" }, description =
            "\"&\" identifier of the run, shared by all the test cases in this spoon run ", help = true)
    public String runId;

    @Parameter(names = { "--deviceSerial" }, description =
            "\"&\" identifier of the device to run into ", help = true)
    public String deviceSerial;

  }

  private static File cleanFile(String path) {
    if (path == null) {
      return null;
    }
    return new File(path);
  }

  /* JCommander deems it necessary that this class be public. Lame. */
  public static class FileConverter implements IStringConverter<File> {
    @Override public File convert(String s) {
      return cleanFile(s);
    }
  }

  public static class TestSizeConverter
      implements IStringConverter<IRemoteAndroidTestRunner.TestSize> {
    @Override public IRemoteAndroidTestRunner.TestSize convert(String value) {
      try {
        return IRemoteAndroidTestRunner.TestSize.getTestSize(value);
      } catch (IllegalArgumentException e) {
        throw new ParameterException(e.getMessage());
      }
    }
  }

  public static void main(String... args) {
    CommandLineArgs parsedArgs = new CommandLineArgs();
    JCommander jc = new JCommander(parsedArgs);

    try {
      jc.parse(args);
    } catch (ParameterException e) {
      StringBuilder out = new StringBuilder(e.getLocalizedMessage()).append("\n\n");
      jc.usage(out);
      System.err.println(out.toString());
      System.exit(1);
      return;
    }
    if (parsedArgs.help) {
      jc.usage();
      return;
    }

    HtmlRenderer.setPrettify(!parsedArgs.nopretty);

    if ((parsedArgs.aggregate != null &&  !parsedArgs.aggregate.isEmpty()) || 
      (parsedArgs.aggregate_out != null && !parsedArgs.aggregate_out.isEmpty())){
        //Entering aggregation mode
        if (parsedArgs.aggregate == null || parsedArgs.aggregate.isEmpty() ||
         parsedArgs.aggregate_out == null || parsedArgs.aggregate_out.isEmpty()) {
            System.err.println("Parameters missing for aggregation");
            return;
        }
        try {
            HtmlRenderer prev = null;
            for (String el : parsedArgs.aggregate){
                logInfo("Adding result file to aggregate: %s", el);
                HtmlRenderer rn = new HtmlRenderer(SpoonUtils.GSON, new File(el), new File(parsedArgs.aggregate_out), parsedArgs.noresultjson, parsedArgs.nohtml);
                if(prev == null)
                    prev = rn;
                else {
                    logInfo("Aggregating: %s with %s", prev, rn);
                    // doesn't write on parsedArgs.aggregate_out yet
                    prev = prev.aggregate(rn, new File(parsedArgs.aggregate_out), parsedArgs.noresultjson, parsedArgs.nohtml);
                }
            }

            prev.render();
        }
        catch (FileNotFoundException err){
            System.err.println(err);
        }
        logDebug(true,"EXIT-HOOK");
        Runtime.getRuntime().halt(0);
    }

    SpoonRunner.Builder tmpBuilder = new SpoonRunner.Builder() //
        .setTitle(parsedArgs.title)
        .setApplicationApk(parsedArgs.apk)
        .setInstrumentationApk(parsedArgs.testApk)
        .setOutputDirectory(parsedArgs.output)
        .setDebug(parsedArgs.debug)
        .setAndroidSdk(parsedArgs.sdk)
        .setNoAnimations(parsedArgs.noAnimations)
        .setTestSize(parsedArgs.size)
        .setAdbTimeout(parsedArgs.adbTimeoutSeconds * 1000)
        .setFailIfNoDeviceConnected(parsedArgs.failIfNoDeviceConnected)
        .setSubpackageName(parsedArgs.subpackageName)
        .setClassName(parsedArgs.className)
        .setMethodName(parsedArgs.methodName)
        .setNoInstall(parsedArgs.noinstall)
        .setRunId((parsedArgs.runId != null && !parsedArgs.runId.equals("") ? parsedArgs.runId : "defaultid" ))
        .setFilterLog(parsedArgs.filterLog);
        
    SpoonRunner spoonRunner = null;

    if(parsedArgs.deviceSerial != null && !parsedArgs.deviceSerial.equals("")) {
      spoonRunner = tmpBuilder
        .addDevice(parsedArgs.deviceSerial)
        .build();
    }
    else {
      spoonRunner = tmpBuilder
        .useAllAttachedDevices()
        .build();
    }

    if (!spoonRunner.run(parsedArgs) && parsedArgs.failOnFailure) {
    	logDebug(true,"EXIT-HOOK");
    	//TODO: WORKAROUND FOR FIXING VM NOT QUITTING
    	Runtime.getRuntime().halt(1);
   	  // System.exit(1);
    }
    else {
    	logDebug(true,"EXIT-HOOK");
    	//TODO: WORKAROUND FOR FIXING VM NOT QUITTING
    	Runtime.getRuntime().halt(0);
   	// System.exit(0);
    }
  }
}
