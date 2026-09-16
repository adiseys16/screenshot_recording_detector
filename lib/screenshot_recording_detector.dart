import 'dart:async';
import 'package:flutter/services.dart';
import 'models/detection_event.dart';
import 'screenshot_recording_detector_platform_interface.dart';

/// Foreground capture notifications. Legacy Android screenshot detection is
/// heuristic; Android recording detection requires API 35+. No global recorder
/// detection or guarantee against every capture mechanism is provided.
class ScreenshotRecordingDetector {
  static ScreenshotRecordingDetectorPlatform get _platform =>
      ScreenshotRecordingDetectorPlatform.instance;

  /// Safe to repeat. Initialization success does not guarantee permission or
  /// active Activity availability: inspect [detectionStatus]. Subscribe first.
  static Future<void> initialize() async {
    await _platform.initialize();
  }

  static Stream<DetectionEvent> get detectionStream =>
      _platform.detectionStream.map(DetectionEvent.fromMap);

  /// Compatibility API: false also means unsupported/unavailable on Android.
  /// Never interpret false as proof that capture cannot happen.
  /// Use [detectionStatus] for nullable state and capability information.
  static Future<bool> get isScreenRecording async {
    try {
      return await _platform.isScreenRecording;
    } on PlatformException {
      return false;
    }
  }

  /// Android diagnostic snapshot. On unchanged iOS/older implementations,
  /// returns statusAvailable:false (not a claim that detection is unsupported).
  static Future<Map<String, dynamic>> get detectionStatus =>
      _platform.detectionStatus;

  /// Android only. If no Activity exists, the preference is stored until attach.
  /// dispose() stops detection but does NOT remove the security preference.
  /// Call setBlockScreenshots(false) explicitly before dispose to release it.
  static Future<void> setBlockScreenshots(bool block) =>
      _platform.setBlockScreenshots(block);

  /// Stops native monitoring. Separately cancel your stream subscriptions.
  static Future<void> dispose() => _platform.dispose();
}
