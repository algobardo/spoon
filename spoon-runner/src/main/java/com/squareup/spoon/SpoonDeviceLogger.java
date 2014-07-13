package com.squareup.spoon;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.logcat.LogCatListener;
import com.android.ddmlib.logcat.LogCatMessage;
import com.android.ddmlib.logcat.LogCatReceiverTask;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SpoonDeviceLogger implements LogCatListener {
  private static final String TEST_RUNNER = "TestRunner";
  private static final Pattern MESSAGE_START = Pattern.compile("started: ([^(]+)\\(([^)]+)\\)");
  private static final Pattern MESSAGE_END = Pattern.compile("finished: [^(]+\\([^)]+\\)");

  private final List<LogCatMessage> messages;
  private final LogCatReceiverTask logCatReceiverTask;
  private final List<String> filterTags;

  public SpoonDeviceLogger(IDevice device, List<String> filterTags) {
    messages = new ArrayList<LogCatMessage>();
    this.filterTags = filterTags;
    logCatReceiverTask = new LogCatReceiverTask(device);
    logCatReceiverTask.addLogCatListener(this);

    // Start a background thread to monitor the device logs. This will exit when we call stop below.
    new Thread(logCatReceiverTask).start();
  }

  private boolean nonEmptyIntersection(List one, List two){
    boolean found = false;
    for (Iterator it = one.iterator(); it.hasNext(); ) {
      if(two.contains(it.next())){
        found = true;
        break;
      }
    }
    return found;
  }

  @Override public void log(List<LogCatMessage> msgList) {
    synchronized (messages) {
      for (Iterator<LogCatMessage> it = msgList.iterator(); it.hasNext(); ) {
        LogCatMessage msg = it.next();
        List<String> curTags = Arrays.asList(msg.getTag().split("&"));
        // Only add if no filterTags contains no tag or the current tag is contained in filterTags
        if(filterTags == null || nonEmptyIntersection(filterTags, curTags) || curTags.contains("TestRunner")){
          messages.add(msg);
        }
      }
    }
  }

  public Map<DeviceTest, List<LogCatMessage>> getParsedLogs() {
    logCatReceiverTask.stop();

    Map<DeviceTest, List<LogCatMessage>> logs = new HashMap<DeviceTest, List<LogCatMessage>>();
    DeviceTest current = null;
    String pid = null;
    synchronized (messages) {
      for (LogCatMessage message : messages) {
        if (current == null) {
          Matcher match = MESSAGE_START.matcher(message.getMessage());
          if (match.matches() && TEST_RUNNER.equals(message.getTag())) {
            current = new DeviceTest(match.group(2), match.group(1));
            pid = message.getPid();

            List<LogCatMessage> deviceLogMessages = new ArrayList<LogCatMessage>();
            deviceLogMessages.add(message);
            logs.put(current, deviceLogMessages);
          }
        } else {
          //Log everything
          //if (pid.equals(message.getPid())) {
            logs.get(current).add(message);
          //}

          Matcher match = MESSAGE_END.matcher(message.getMessage());
          if (match.matches() && TEST_RUNNER.equals(message.getTag())) {
            current = null;
            pid = null;
          }
        }
      }
    }
    return logs;
  }
}
