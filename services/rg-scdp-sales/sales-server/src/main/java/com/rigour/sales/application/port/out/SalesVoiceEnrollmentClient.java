package com.rigour.sales.application.port.out;

import java.util.List;

public interface SalesVoiceEnrollmentClient {
    Enrollment enroll(byte[] audio);
    record Enrollment(String model,List<Double> embedding,long speechMs) {}
}
