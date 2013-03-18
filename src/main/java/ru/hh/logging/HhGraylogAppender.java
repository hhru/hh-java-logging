package ru.hh.logging;

import me.moocar.logbackgelf.GelfAppender;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Is a {@link GelfAppender}, adds MDC {@code request_id} field,
 * sets facility to <tt><s>graylog.</s>appendername.log</tt>,
 * sets graylog server host to {@code graylog.host} system property.
 */
public class HhGraylogAppender extends GelfAppender {

  private static final Pattern nameRe = Pattern.compile("^graylog\\.(.+)$");

  @Override
  public void start() {

    addAdditionalField("request_id:_request_id");

    if (getFacility() == null || getFacility().equals("GELF")) {
      String facility = getName();
      Matcher m = nameRe.matcher(facility);
      if (m.matches()) {
        facility = m.group(1);
      }
      setFacility(facility + ".log");
    }

    if (getGraylog2ServerHost() == null) {
      setGraylog2ServerHost(System.getProperty("graylog.host"));
    }

    super.start();
  }
}

