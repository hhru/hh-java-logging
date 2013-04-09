package ru.hh.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.rolling.DefaultTimeBasedFileNamingAndTriggeringPolicy;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import com.google.common.base.Preconditions;
import org.apache.commons.lang.StringUtils;
import java.util.Random;

/**
 * Is a combo of {@link RollingFileAppender}, {@link FixedWindowRollingPolicy},
 * {@link DefaultTimeBasedFileNamingAndTriggeringPolicy}, {@link TimeBasedRollingPolicy}.
 *
 * <p>Main file is set to {@code $log.dir/appendername.log}. Rolled file is set to {@code $log.dir/appendername.%i.gz}.
 * Layout pattern is set to {@code $log.pattern}.
 *
 * <p>Other properties, same value for all appenders, but can be overriden by appender attribute (see setter methods).
 * If not specified as a property or an attribute in logback config, default value is used.
 *
 * Property {@code $log.index.min} See FixedWindowRollingPolicy. Default 1.
 *
 * Property {@code $log.index.max} See FixedWindowRollingPolicy. Default 1.
 *
 * Property {@code $log.roll.hour} Start roll within 10 min after roll.hour roll.minute. Default hour 0.
 *
 * Property {@code $log.roll.minute} Start roll within 10 min after roll.hour roll.minute. Default minute 0.
 *
 * Property {@code $log.roll.compress} To compress or not to compress ? Default false.
 *
 * Property {@code $log.immediate.flush} Sync log to disk for each log line. Default false.
 *
 * Property {@code $log.collect.packaging.info} Collect packaging info when logging, sometimes
 * causes big overhead. Default is provided by logback (expected to be always true).
 *
 */
public class HhRollingAppender extends RollingFileAppender<ILoggingEvent> {

  // keep just one rolled log by default
  public static final int DEFAULT_MIN_INDEX = 1;
  public static final int DEFAULT_MAX_INDEX = 1;

  public static final int DEFAULT_ROLL_HOUR = 0;
  public static final int DEFAULT_ROLL_MINUTE = 0;

  public static final boolean DEFAULT_IMMEDIATE_FLUSH = false;
  public static final boolean DEFAULT_COMPRESS = false;

  private Integer minIndex;
  private Integer maxIndex;

  // these two parameters are especially useful for testing
  private Integer rollHour = null;
  private Integer rollMinute = null;

  private Boolean compress;
  private Boolean immediateFlush;
  private Boolean collectPackagingInfo;

  private String fileNamePattern = "%d{yyyy-MM-dd}";
  private String pattern;

  // up to 10 mins random offset to start all rolling on the current
  // box. Needed to avoid simultaneous log rolling on all boxes. Each
  // next appender increases offset value to have a different start
  // time in different appenders.
  private static long nextAppenderRollOffset = new Random().nextInt(600*1000); // 600 seconds
  // counter used to initialize offset for each logger with a different value

  private static final long MIN_ADDITIONAL_ROLL_OFFSET = 1000; // 1 second

  private final long rollOffset;

  public HhRollingAppender() {
    synchronized (HhRollingAppender.class) {
      rollOffset = nextAppenderRollOffset;
      nextAppenderRollOffset += MIN_ADDITIONAL_ROLL_OFFSET + new Random(nextAppenderRollOffset).nextInt(1000);
    }
  }

  public Integer getMinIndex() {
    return minIndex;
  }

  public void setMinIndex(int minIndex) {
    this.minIndex = minIndex;
  }

  public Integer getMaxIndex() {
    return maxIndex;
  }

  public void setMaxIndex(int maxIndex) {
    this.maxIndex = maxIndex;
  }

  public Integer getRollHour() {
    return rollHour;
  }

  public void setRollHour(int rollHour) {
    if (rollHour < 0 || rollHour > 23) {
      throw new IllegalArgumentException("The \"RollHour\" value must be in 0...23 range");
    }
    this.rollHour = rollHour;
  }

  public Integer getRollMinute() {
    return rollMinute;
  }

  public void setRollMinute(int rollMinute) {
    if (rollMinute < 0 || rollMinute > 59) {
      throw new IllegalArgumentException("The \"RollMinute\" value must be in 0...59 range");
    }
    this.rollMinute = rollMinute;
  }

  public Boolean getCompress() {
    return compress;
  }

  public void setCompress(boolean compress) {
    this.compress = compress;
  }

  public Boolean getImmediateFlush() {
    return immediateFlush;
  }

  public void setImmediateFlush(boolean immediateFlush) {
    this.immediateFlush = immediateFlush;
  }

  public Boolean getCollectPackagingInfo() {
    return collectPackagingInfo;
  }

  public void setCollectPackagingInfo(boolean collectPackagingInfo) {
    this.collectPackagingInfo = collectPackagingInfo;
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

  private long calcOffset() {
    return rollOffset + (rollHour * 60 + rollMinute) * 60 * 1000;
  }

  private int calcParameter(Integer parameter, String propName, int defaultValue) {
    final String propValue = context.getProperty(propName);
    if (parameter != null) {
      return parameter;
    } else if (!StringUtils.isBlank(propValue)) {
      return Integer.valueOf(propValue.trim());
    } else {
      return defaultValue;
    }
  }

  private boolean calcParameter(Boolean parameter, String propName, boolean defaultValue) {
    final String propValue = context.getProperty(propName);
    if (parameter != null) {
      return parameter;
    } else if (!StringUtils.isBlank(propValue)) {
      return Boolean.valueOf(propValue.trim());
    } else {
      return defaultValue;
    }
  }

  @Override
  public void start() {
    String propLogdir = context.getProperty("log.dir");
    if (propLogdir == null) {
      propLogdir = "logs";
      addWarn("log.dir is not specified, using `" + propLogdir + "'");
    }
    minIndex = calcParameter(minIndex, "log.index.min", DEFAULT_MIN_INDEX);
    maxIndex = calcParameter(maxIndex, "log.index.max", DEFAULT_MAX_INDEX);
    rollHour = calcParameter(rollHour, "log.roll.hour", DEFAULT_ROLL_HOUR);
    rollMinute = calcParameter(rollMinute, "log.roll.minute", DEFAULT_ROLL_MINUTE);
    compress = calcParameter(compress, "log.roll.compress", DEFAULT_COMPRESS);
    immediateFlush = calcParameter(immediateFlush, "log.immediate.flush", DEFAULT_IMMEDIATE_FLUSH);

    final String propPattern = context.getProperty("log.pattern");
    final String propPackagingInfo = context.getProperty("log.collect.packaging.info");

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
      final String fileNameEnding;
      if (compress) {
        fileNameEnding = ".%i.gz";
      } else {
        fileNameEnding = ".%i";
      }
      rolling.setFileNamePattern(fileName + fileNameEnding);
      rolling.setMinIndex(minIndex);
      rolling.setMaxIndex(maxIndex);
      rolling.setParent(this);
      setRollingPolicy(rolling);
      rolling.start();
    }

    if (getTriggeringPolicy() == null) {
      DefaultTimeBasedFileNamingAndTriggeringPolicy<ILoggingEvent> triggering = new DefaultTimeBasedFileNamingAndTriggeringPolicy<ILoggingEvent>() {
        private final long DAY_MILLIS = 24 * 60 * 60 * 1000;
        @Override
        protected void computeNextCheck() {
          super.computeNextCheck();
          nextCheck += calcOffset();
          if (nextCheck - DAY_MILLIS > getCurrentTime()) {
            nextCheck -= DAY_MILLIS;
          }
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
      if (immediateFlush) {
        encoder.setImmediateFlush(true);
      } else {
        encoder.setImmediateFlush(false);
      }
      setEncoder(encoder);
      encoder.start();
    }

    if (collectPackagingInfo != null) {
      ((LoggerContext) context).setPackagingDataEnabled(collectPackagingInfo);
    } else if (propPackagingInfo != null) {
      ((LoggerContext) context).setPackagingDataEnabled(Boolean.valueOf(propPackagingInfo.trim()));
    }

    super.start();
  }

}
