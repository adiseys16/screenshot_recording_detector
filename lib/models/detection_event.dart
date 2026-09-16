enum CaptureType { screenshot, recording, none }

class DetectionEvent {
  final CaptureType type;
  final DateTime? timestamp;
  final bool? isRecording;
  final String platform;

  DetectionEvent({
    required this.type,
    required this.timestamp,
    this.isRecording,
    required this.platform,
  });

  factory DetectionEvent.fromMap(Map<dynamic, dynamic> map) {
    final rawTime = map['timestamp'];
    DateTime? time;
    // Reject malformed/overflowing timestamps instead of crashing event parsing.
    if (rawTime is num && rawTime.isFinite &&
        rawTime.abs() <= 8640000000000000) {
      time = DateTime.fromMillisecondsSinceEpoch(rawTime.toInt());
    }
    return DetectionEvent(
      type: map['type'] == 'screenshot'
          ? CaptureType.screenshot
          : map['type'] == 'recording' ? CaptureType.recording : CaptureType.none,
      timestamp: time,
      isRecording: map['isRecording'] is bool ? map['isRecording'] as bool : null,
      platform: map['platform'] is String ? map['platform'] as String : 'unknown',
    );
  }

  @override
  String toString() => 'DetectionEvent(type: $type, timestamp: $timestamp, '
      'isRecording: $isRecording, platform: $platform)';
}
