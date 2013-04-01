package ru.hh.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.rolling.DefaultTimeBasedFileNamingAndTriggeringPolicy;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import com.google.common.base.Preconditions;

import java.io.File;

/**
 * Is a combo of {@link RollingFileAppender}, {@link FixedWindowRollingPolicy},
 * {@link DefaultTimeBasedFileNamingAndTriggeringPolicy}, {@link TimeBasedRollingPolicy}.
 *
 * <p>Main file is set to {@code $log.dir/appendername.log}. Rolled file is set to {@code $log.dir/appendername.%i.gz}.
 * Layout pattern is set to {@code $log.pattern}.
 *
 */
public class HhRollingAppender extends RollingFileAppender<ILoggingEvent> {

  private int minIndex = 1;
  private int maxIndex = 3;
  private String fileNamePattern = "%d{yyyy-MM-dd}";
  private String pattern;

  public int getMinIndex() {
    return minIndex;
  }

  public void setMinIndex(int minIndex) {
    this.minIndex = minIndex;
  }

  public int getMaxIndex() {
    return maxIndex;
  }

  public void setMaxIndex(int maxIndex) {
    this.maxIndex = maxIndex;
  }

  public String getFileNamePattern() {
    return fileNamePattern;
  }

  public void setFileNamePattern(String fileNamePattern) {
    this.fileNamePattern = fileNamePattern;
  }

  public String getPattern() {
    return pattern;
  }

  public void setPattern(String pattern) {
    this.pattern = pattern;
  }

  @Override
  public void start() {
    final String propLogdir = context.getProperty("log.dir");
    final String propPattern = context.getProperty("log.pattern");

    if (fileName == null) {
      Preconditions.checkArgument(
          !getName().matches(".*(\\.\\.|/|\\\\).*"),
          "appender name cannot have filesystem path elements: %s", getName()
      );
      setFile(String.format("%s/%s.log", propLogdir, getName()));
    }

    if (getRollingPolicy() == null) {
      FixedWindowRollingPolicy rolling = new FixedWindowRollingPolicy();
      rolling.setContext(context);
      rolling.setFileNamePattern(fileName.replaceFirst("\\.log$", ".log.%i.gz"));
      rolling.setMinIndex(minIndex);
      rolling.setMaxIndex(maxIndex);
      rolling.setParent(this);
      setRollingPolicy(rolling);
      rolling.start();
    }

    if (getTriggeringPolicy() == null) {
      DefaultTimeBasedFileNamingAndTriggeringPolicy<ILoggingEvent> triggering = new DefaultTimeBasedFileNamingAndTriggeringPolicy<ILoggingEvent>() {
        @Override
        public boolean isTriggeringEvent(File activeFile, ILoggingEvent event) {
          // rolling turned off until time-based policy is fixed to roll strictly at 03:00 daily.
          return false; //super.isTriggeringEvent(activeFile, event);
        }
      };
      triggering.setContext(context);
      TimeBasedRollingPolicy<ILoggingEvent> rolling = new TimeBasedRollingPolicy<ILoggingEvent>();
      rolling.setContext(context);
      rolling.setTimeBasedFileNamingAndTriggeringPolicy(triggering);
      rolling.setFileNamePattern(fileNamePattern);
      rolling.setParent(this);
      triggering.setTimeBasedRollingPolicy(rolling);
      setTriggeringPolicy(triggering);
      rolling.start();
      triggering.start();
    }

    if (encoder == null) {
      if (pattern == null) {
        pattern = propPattern;
      }
      LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<ILoggingEvent>();
      encoder.setContext(context);
      PatternLayout layout = new PatternLayout();
      layout.setContext(context);
      layout.setPattern(pattern);
      layout.start();
      encoder.setLayout(layout);
      setEncoder(encoder);
      encoder.start();
    }

    super.start();
  }

}

