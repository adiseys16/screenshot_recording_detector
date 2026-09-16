import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'screenshot_recording_detector_platform_interface.dart';

class MethodChannelScreenshotRecordingDetector
    extends ScreenshotRecordingDetectorPlatform {
  @visibleForTesting
  final methodChannel = const MethodChannel('screenshot_recording_detector');
  @visibleForTesting
  final eventChannel = const EventChannel('screenshot_recording_events');

  // One platform broadcast stream per implementation, shared by all consumers.
  late final Stream<Map<dynamic, dynamic>> _events =
      eventChannel.receiveBroadcastStream().map((dynamic event) {
    if (event is! Map) {
      throw const FormatException(
          'Expected a map from screenshot_recording_events');
    }
    return event.cast<dynamic, dynamic>();
  });

  @override
  Future<void> initialize() => methodChannel.invokeMethod<void>('initialize');

  @override
  Stream<Map<dynamic, dynamic>> get detectionStream => _events;

  @override
  Future<bool> get isScreenRecording async =>
      await methodChannel.invokeMethod<bool>('isScreenRecording') ?? false;

  @override
  Future<Map<String, dynamic>> get detectionStatus async {
    try {
      final value = await methodChannel.invokeMapMethod<String, dynamic>(
        'getDetectionStatus',
      );
      return value == null
          ? <String, dynamic>{'statusAvailable': false}
          : <String, dynamic>{...value, 'statusAvailable': true};
    } on MissingPluginException {
      // Unchanged iOS source and older platform implementations are supported.
      return <String, dynamic>{'statusAvailable': false};
    }
  }

  @override
  Future<void> setBlockScreenshots(bool block) =>
      methodChannel.invokeMethod<void>('setBlockScreenshots', block);

  @override
  Future<void> dispose() => methodChannel.invokeMethod<void>('dispose');
}
