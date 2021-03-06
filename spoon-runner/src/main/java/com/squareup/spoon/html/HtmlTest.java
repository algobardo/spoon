package com.squareup.spoon.html;

import com.squareup.spoon.DeviceDetails;
import com.squareup.spoon.DeviceResult;
import com.squareup.spoon.DeviceTest;
import com.squareup.spoon.DeviceTestResult;
import com.squareup.spoon.SpoonSummary;
import com.squareup.spoon.misc.StackTrace;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.squareup.spoon.DeviceTestResult.Status;

/** Model for representing a {@code test.html} page. */
final class HtmlTest {
  public static HtmlTest from(DeviceTest test, SpoonSummary summary, File output) {
    int deviceCount = 0;
    int testsPassed = 0;
    int duration = 0;
    List<TestResult> devices = new ArrayList<TestResult>();
    for (Map.Entry<String, DeviceResult> entry : summary.getResults().entrySet()) {
      DeviceResult deviceResult = entry.getValue();
      DeviceTestResult testResult = deviceResult.getTestResults().get(test);
      if (testResult != null) {
        deviceCount += 1;
        if (testResult.getOverallStatus() == Status.PASS) {
          testsPassed += 1;
          duration += testResult.getDuration();
        }
        String serial = entry.getKey();
        DeviceDetails details = deviceResult.getDeviceDetails();
        String name = (details != null) ? details.getName() : serial;
        devices.add(TestResult.from(serial, name, testResult, output));
      }
    }

    int testsFailed = deviceCount - testsPassed;
    String totalDevices = deviceCount + " device" + (deviceCount != 1 ? "s" : "");
    String title = HtmlUtils.prettifyMethodName(test.getMethodName());

    StringBuilder subtitle = new StringBuilder();
    subtitle.append("Ran on ")
        .append(totalDevices)
        .append(" with ")
        .append(testsPassed)
        .append(" passing and ")
        .append(testsFailed)
        .append(" failing");
    if (testsPassed > 0) {
      subtitle.append(" in an average of ")
        .append(HtmlUtils.humanReadableDuration(duration / testsPassed));
    }

    String className = test.getClassName();
    String methodName = test.getMethodName();

    return new HtmlTest(title, subtitle.toString(), className, methodName, devices);
  }

  public final String title;
  public final String subtitle;
  public final String className;
  public final String methodName;
  public final List<TestResult> devices;

  HtmlTest(String title, String subtitle, String className, String methodName,
      List<TestResult> devices) {
    this.title = title;
    this.subtitle = subtitle;
    this.className = className;
    this.methodName = methodName;
    this.devices = devices;
  }

  static final class TestResult implements Comparable<TestResult> {
    static TestResult from(String serial, String name, DeviceTestResult result, File output) {
      String status = HtmlUtils.getStatusCssClass(result);

      List<HtmlUtils.Screenshot> screenshots = new ArrayList<HtmlUtils.Screenshot>();
      for (File screenshot : result.getScreenshots()) {
        screenshots.add(HtmlUtils.getScreenshot(screenshot, output));
      }
      String animatedGif = HtmlUtils.createRelativeUri(result.getAnimatedGif(), output);
      List<HtmlUtils.ExceptionInfo> exception = HtmlUtils.processStackTrace(result.getException());

      List<String> detailedStatus = HtmlUtils.getStatusesCssClass(result.getStatus());
      
      List<IdsTriples> triples = new ArrayList<IdsTriples>();
      
      for(int i = 0; i < detailedStatus.size(); i++) {
    	  IdsTriples triple = new IdsTriples();
    	  triple.status = detailedStatus.get(i);
    	  triple.exception = exception.get(i);
    	  triple.runId = result.getRunIds().get(i);
    	  triples.add(triple);
      }
      
      return new TestResult(name, serial, status, screenshots, animatedGif, triples);
    }

    public final String name;
    public final String serial;
    public final String status;
    public final boolean hasScreenshots;
    public final List<HtmlUtils.Screenshot> screenshots;
    public final String animatedGif;
    public final List<IdsTriples> triples;

    TestResult(String name, String serial, String status, List<HtmlUtils.Screenshot> screenshots,
        String animatedGif, List<IdsTriples> triples) {
      this.name = name;
      this.serial = serial;
      this.status = status;
      this.hasScreenshots = !screenshots.isEmpty();
      this.screenshots = screenshots;
      this.animatedGif = animatedGif;
      this.triples = triples;
    }

    @Override public int compareTo(TestResult other) {
      return name.compareTo(other.name);
    }
  }
  
  static final class IdsTriples  implements Comparable<IdsTriples> {
	  public String runId;
	  public HtmlUtils.ExceptionInfo exception;
	  public String status;
	  
	  @Override public int compareTo(IdsTriples other) {
	      return runId.compareTo(other.runId);
	    }
  }
}
