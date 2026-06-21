import { useCallback, useEffect, useState } from 'react';
import { StatusBar } from 'expo-status-bar';
import {
  Alert,
  NativeModules,
  Platform,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';

const OPACITY_MIN = 0.1;
const OPACITY_MAX = 1;
const OPACITY_STEP = 0.05;
const OPACITY_MIN_PERCENT = 10;
const OPACITY_MAX_PERCENT = 100;
const clampOpacity = (value) => Math.min(OPACITY_MAX, Math.max(OPACITY_MIN, Number(value) || OPACITY_MIN));

const snapOpacity = (value) => {
  const clamped = clampOpacity(value);
  const stepIndex = Math.round((clamped - OPACITY_MIN) / OPACITY_STEP);
  return Number((OPACITY_MIN + stepIndex * OPACITY_STEP).toFixed(2));
};

const opacityToPercent = (value) => Math.round(snapOpacity(value) * 100);

const opacityToRatio = (value) => {
  const snapped = snapOpacity(value);
  return Math.min(1, Math.max(0, (snapped - OPACITY_MIN) / (OPACITY_MAX - OPACITY_MIN)));
};

const opacityFromLocation = (locationX, width) => {
  const safeWidth = Math.max(1, width || 1);
  const ratio = Math.min(1, Math.max(0, locationX / safeWidth));
  return snapOpacity(OPACITY_MIN + ratio * (OPACITY_MAX - OPACITY_MIN));
};
const KEYBOARD_SHORTCUT_ACTION_TELEGRAM_TO_GPT = 'telegram_to_gpt';
const KEYBOARD_SHORTCUT_ACTION_GPT_TO_TELEGRAM = 'gpt_to_telegram';
const DEFAULT_KEYBOARD_SHORTCUT_SETTINGS = {
  telegramToGptLabel: 'Ctrl + Enter',
  gptToTelegramLabel: 'Ctrl + Shift + Enter',
  captureAction: '',
};

const API_USAGE_HOUR_MS = 60 * 60 * 1000;
const API_USAGE_MINUTE_MS = 60 * 1000;
const API_USAGE_ROWS_PER_HOUR = 60;

export default function App() {
  const [debugLogs, setDebugLogs] = useState('');
  const [widgetOpacity, setWidgetOpacity] = useState(1);
  const [collapsedOpacity, setCollapsedOpacity] = useState(0.85);
  const [widgetOpacityTrackWidth, setWidgetOpacityTrackWidth] = useState(1);
  const [collapsedOpacityTrackWidth, setCollapsedOpacityTrackWidth] = useState(1);
  const [keyboardShortcutSettings, setKeyboardShortcutSettings] = useState(DEFAULT_KEYBOARD_SHORTCUT_SETTINGS);
  const [keyboardShortcutCaptureAction, setKeyboardShortcutCaptureAction] = useState('');
  const [keyboardShortcutMessage, setKeyboardShortcutMessage] = useState('');
  const [deepSeekApiKey, setDeepSeekApiKey] = useState('');
  const [deepSeekKeyStatus, setDeepSeekKeyStatus] = useState('$KEY');
  const [deepSeekBalanceStatus, setDeepSeekBalanceStatus] = useState('$KEY');
  const [apiUsageHourOffset, setApiUsageHourOffset] = useState(0);
  const [apiUsageRecords, setApiUsageRecords] = useState([]);

  const getNativeModule = () => NativeModules.CopyBridgeNativeModule;

  const loadDebugLogs = useCallback(async () => {
    const nativeModule = getNativeModule();
    if (Platform.OS !== 'android') {
      setDebugLogs('Android APK\uc5d0\uc11c\ub9cc \ub85c\uadf8\ub97c \ud655\uc778\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4.');
      return;
    }
    if (!nativeModule || typeof nativeModule.getDebugLogs !== 'function') {
      setDebugLogs('\ub124\uc774\ud2f0\ube0c \ub85c\uadf8 \uae30\ub2a5\uc774 \uc544\uc9c1 \uc5f0\uacb0\ub418\uc9c0 \uc54a\uc558\uc2b5\ub2c8\ub2e4.');
      return;
    }
    try {
      const logs = await nativeModule.getDebugLogs();
      setDebugLogs(logs || '');
    } catch (error) {
      setDebugLogs('\ub85c\uadf8\ub97c \ubd88\ub7ec\uc624\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4.');
    }
  }, []);

    const loadOpacitySettings = useCallback(async () => {
    const nativeModule = getNativeModule();
    if (Platform.OS !== 'android' || !nativeModule || typeof nativeModule.getOpacitySettings !== 'function') return;
    try {
      const settings = await nativeModule.getOpacitySettings();
      if (settings && typeof settings.widgetOpacity === 'number') setWidgetOpacity(settings.widgetOpacity);
      if (settings && typeof settings.collapsedOpacity === 'number') setCollapsedOpacity(settings.collapsedOpacity);
    } catch (error) {}
  }, []);

const loadKeyboardShortcutSettings = useCallback(async () => {
    const nativeModule = getNativeModule();
    if (Platform.OS !== 'android' || !nativeModule || typeof nativeModule.getKeyboardShortcutSettings !== 'function') {
      setKeyboardShortcutSettings(DEFAULT_KEYBOARD_SHORTCUT_SETTINGS);
      setKeyboardShortcutCaptureAction('');
      return;
    }

    try {
      const settings = await nativeModule.getKeyboardShortcutSettings();
      const nextSettings = {
        telegramToGptLabel: settings?.telegramToGptLabel || DEFAULT_KEYBOARD_SHORTCUT_SETTINGS.telegramToGptLabel,
        gptToTelegramLabel: settings?.gptToTelegramLabel || DEFAULT_KEYBOARD_SHORTCUT_SETTINGS.gptToTelegramLabel,
        captureAction: settings?.captureAction || '',
      };

      setKeyboardShortcutSettings(nextSettings);
      setKeyboardShortcutCaptureAction(nextSettings.captureAction);
    } catch (error) {
      setKeyboardShortcutSettings(DEFAULT_KEYBOARD_SHORTCUT_SETTINGS);
      setKeyboardShortcutCaptureAction('');
    }
  }, []);

  const refreshKeyboardShortcutSettingsFromNative = async (settings) => {
    const nextSettings = {
      telegramToGptLabel: settings?.telegramToGptLabel || DEFAULT_KEYBOARD_SHORTCUT_SETTINGS.telegramToGptLabel,
      gptToTelegramLabel: settings?.gptToTelegramLabel || DEFAULT_KEYBOARD_SHORTCUT_SETTINGS.gptToTelegramLabel,
      captureAction: settings?.captureAction || '',
    };

    setKeyboardShortcutSettings(nextSettings);
    setKeyboardShortcutCaptureAction(nextSettings.captureAction);
  };

  const startKeyboardShortcutCapture = async (action, label) => {
    const nativeModule = getNativeModule();
    if (Platform.OS !== 'android') {
      Alert.alert('Android\uc804\uc6a9 \uae30\ub2a5', '\ubb3c\ub9ac \ud0a4\ubcf4\ub4dc \ub2e8\ucd95\ud0a4 \uc124\uc815\uc740 Android APK\uc5d0\uc11c \uc0ac\uc6a9\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4.');
      return;
    }

    if (!nativeModule || typeof nativeModule.startKeyboardShortcutCapture !== 'function') {
      Alert.alert('\ub124\uc774\ud2f0\ube0c \uc5f0\uacb0 \ud544\uc694', '\ubb3c\ub9ac \ud0a4\ubcf4\ub4dc \ub2e8\ucd95\ud0a4 \uc124\uc815 \uae30\ub2a5\uc774 \uc544\uc9c1 \uc5f0\uacb0\ub418\uc9c0 \uc54a\uc558\uc2b5\ub2c8\ub2e4.');
      return;
    }

    try {
      const settings = await nativeModule.startKeyboardShortcutCapture(action);
      await refreshKeyboardShortcutSettingsFromNative(settings);
      setKeyboardShortcutMessage(label + ' \ubcc0\uacbd \ub300\uae30 \uc911\uc785\ub2c8\ub2e4. \ubb3c\ub9ac \ud0a4\ubcf4\ub4dc\uc5d0\uc11c \uc6d0\ud558\ub294 \uc870\ud569\uc744 \ub204\ub974\uc138\uc694.');
    } catch (error) {
      Alert.alert('\uc124\uc815 \uc2e4\ud328', '\ub2e8\ucd95\ud0a4 \ubcc0\uacbd \ub300\uae30 \uc0c1\ud0dc\ub97c \uc2dc\uc791\ud558\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4.');
    }
  };

  const cancelKeyboardShortcutCapture = async () => {
    const nativeModule = getNativeModule();
    if (!nativeModule || typeof nativeModule.cancelKeyboardShortcutCapture !== 'function') return;

    try {
      const settings = await nativeModule.cancelKeyboardShortcutCapture();
      await refreshKeyboardShortcutSettingsFromNative(settings);
      setKeyboardShortcutMessage('\ub2e8\ucd95\ud0a4 \ubcc0\uacbd\uc744 \ucde8\uc18c\ud588\uc2b5\ub2c8\ub2e4.');
    } catch (error) {
      Alert.alert('\ucde8\uc18c \uc2e4\ud328', '\ub2e8\ucd95\ud0a4 \ubcc0\uacbd \ub300\uae30\ub97c \ucde8\uc18c\ud558\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4.');
    }
  };

  const resetKeyboardShortcuts = async () => {
    const nativeModule = getNativeModule();
    if (Platform.OS !== 'android') {
      Alert.alert('Android\uc804\uc6a9 \uae30\ub2a5', '\ubb3c\ub9ac \ud0a4\ubcf4\ub4dc \ub2e8\ucd95\ud0a4 \uc124\uc815\uc740 Android APK\uc5d0\uc11c \uc0ac\uc6a9\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4.');
      return;
    }

    if (!nativeModule || typeof nativeModule.resetKeyboardShortcuts !== 'function') {
      Alert.alert('\ub124\uc774\ud2f0\ube0c \uc5f0\uacb0 \ud544\uc694', '\ub2e8\ucd95\ud0a4 \ucd08\uae30\ud654 \uae30\ub2a5\uc774 \uc544\uc9c1 \uc5f0\uacb0\ub418\uc9c0 \uc54a\uc558\uc2b5\ub2c8\ub2e4.');
      return;
    }

    try {
      const settings = await nativeModule.resetKeyboardShortcuts();
      await refreshKeyboardShortcutSettingsFromNative(settings);
      setKeyboardShortcutMessage('\uae30\ubcf8 \ub2e8\ucd95\ud0a4\ub85c \ucd08\uae30\ud654\ud588\uc2b5\ub2c8\ub2e4.');
    } catch (error) {
      Alert.alert('\ucd08\uae30\ud654 \uc2e4\ud328', '\ub2e8\ucd95\ud0a4\ub97c \uae30\ubcf8\uac12\uc73c\ub85c \ucd08\uae30\ud654\ud558\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4.');
    }
  };

  const renderKeyboardShortcutRow = ({
    title,
    description,
    value,
    action,
  }) => {
    const isCapturing = keyboardShortcutCaptureAction === action;

    return (
      <View style={styles.shortcutRow}>
        <View style={styles.shortcutTextBlock}>
          <Text style={styles.shortcutTitle}>{title}</Text>
          <Text style={styles.shortcutSubtext}>{description}</Text>
          <Text style={styles.shortcutValue}>{value}</Text>
        </View>

        <TouchableOpacity
          style={[
            styles.shortcutChangeButton,
            isCapturing && styles.shortcutChangeButtonActive,
          ]}
          activeOpacity={0.82}
          onPress={() => startKeyboardShortcutCapture(action, title)}
        >
          <Text
            style={[
              styles.shortcutChangeButtonText,
              isCapturing && styles.shortcutChangeButtonTextActive,
            ]}
          >
            {isCapturing ? '\uc785\ub825 \ub300\uae30' : '\ubcc0\uacbd'}
          </Text>
        </TouchableOpacity>
      </View>
    );
  };

  const loadDeepSeekSettings = useCallback(async () => {
    const nativeModule = getNativeModule();
    if (Platform.OS !== 'android' || !nativeModule || typeof nativeModule.getDeepSeekApiKeyStatus !== 'function') {
      setDeepSeekKeyStatus('$KEY');
      setDeepSeekBalanceStatus('$KEY');
      return;
    }
    try {
      const status = await nativeModule.getDeepSeekApiKeyStatus();
      if (status?.hasKey) {
        setDeepSeekKeyStatus(status.maskedKey || '저장됨');
        setDeepSeekBalanceStatus('$...');
      } else {
        setDeepSeekKeyStatus('$KEY');
        setDeepSeekBalanceStatus('$KEY');
      }
    } catch (error) {
      setDeepSeekKeyStatus('$KEY');
      setDeepSeekBalanceStatus('$KEY');
    }
  }, []);

  const saveDeepSeekApiKey = async () => {
    const nativeModule = getNativeModule();
    if (Platform.OS !== 'android') {
      Alert.alert('Android 전용 기능', 'DeepSeek API Key 저장은 Android APK에서 사용할 수 있습니다.');
      return;
    }
    if (!nativeModule || typeof nativeModule.saveDeepSeekApiKey !== 'function') {
      Alert.alert('네이티브 연결 필요', 'DeepSeek API Key 저장 기능이 아직 연결되지 않았습니다.');
      return;
    }
    const trimmedKey = deepSeekApiKey.trim();
    if (!trimmedKey) {
      Alert.alert('입력 필요', 'DeepSeek API Key를 입력해주세요.');
      return;
    }
    try {
      await nativeModule.saveDeepSeekApiKey(trimmedKey);
      setDeepSeekApiKey('');
      await loadDeepSeekSettings();
      Alert.alert('저장 완료', 'DeepSeek API Key를 저장했습니다.');
    } catch (error) {
      Alert.alert('저장 실패', 'DeepSeek API Key를 저장하지 못했습니다.');
    }
  };

  const loadApiUsageRecords = useCallback(async () => {
    const nativeModule = getNativeModule();
    if (Platform.OS !== 'android' || !nativeModule || typeof nativeModule.getApiUsageMinutes !== 'function') {
      setApiUsageRecords([]);
      return;
    }
    try {
      const raw = await nativeModule.getApiUsageMinutes();
      const parsed = JSON.parse(raw || '[]');
      if (!Array.isArray(parsed)) {
        setApiUsageRecords([]);
        return;
      }
      const safeRecords = parsed
        .map((item) => ({
          minuteStart: Number(item.minuteStart),
          usageUsd: Math.max(0, Number(item.usageUsd || 0)),
          balanceUsd: item.balanceUsd === null || item.balanceUsd === undefined
            ? null
            : Number(item.balanceUsd),
        }))
        .filter((item) => (
          Number.isFinite(item.minuteStart) &&
          Number.isFinite(item.usageUsd) &&
          (item.balanceUsd === null || Number.isFinite(item.balanceUsd))
        ));
      setApiUsageRecords(safeRecords);
    } catch (error) {
      setApiUsageRecords([]);
    }
  }, []);

  const checkDeepSeekBalance = async () => {
    const nativeModule = getNativeModule();
    if (Platform.OS !== 'android') {
      Alert.alert('Android 전용 기능', 'DeepSeek 잔액 조회는 Android APK에서 사용할 수 있습니다.');
      return;
    }
    if (!nativeModule || typeof nativeModule.fetchDeepSeekBalance !== 'function') {
      Alert.alert('네이티브 연결 필요', 'DeepSeek 잔액 조회 기능이 아직 연결되지 않았습니다.');
      return;
    }
    setDeepSeekBalanceStatus('$...');
    try {
      const result = await nativeModule.fetchDeepSeekBalance();
      if (result?.ok && typeof result.balance === 'number') {
        setDeepSeekBalanceStatus('$' + result.balance.toFixed(2));
      } else {
        setDeepSeekBalanceStatus('$ERR');
      }
    } catch (error) {
      setDeepSeekBalanceStatus('$ERR');
    } finally {
      await loadApiUsageRecords();
    }
  };

  const previewWidgetOpacityValue = (value) => {
    setWidgetOpacity(snapOpacity(value));
  };

  const previewCollapsedOpacityValue = (value) => {
    setCollapsedOpacity(snapOpacity(value));
  };

  const setWidgetOpacityValue = async (value) => {
    const nextValue = snapOpacity(value);
    setWidgetOpacity(nextValue);

    const nativeModule = getNativeModule();
    if (!nativeModule || typeof nativeModule.setWidgetOpacity !== 'function') return;

    try {
      const savedValue = await nativeModule.setWidgetOpacity(nextValue);
      setWidgetOpacity(typeof savedValue === 'number' ? snapOpacity(savedValue) : nextValue);
    } catch (error) {
      Alert.alert('설정 실패', '위젯 투명도를 저장하지 못했습니다.');
    }
  };

  const setCollapsedOpacityValue = async (value) => {
    const nextValue = snapOpacity(value);
    setCollapsedOpacity(nextValue);

    const nativeModule = getNativeModule();
    if (!nativeModule || typeof nativeModule.setCollapsedOpacity !== 'function') return;

    try {
      const savedValue = await nativeModule.setCollapsedOpacity(nextValue);
      setCollapsedOpacity(typeof savedValue === 'number' ? snapOpacity(savedValue) : nextValue);
    } catch (error) {
      Alert.alert('설정 실패', '최소화 아이콘 투명도를 저장하지 못했습니다.');
    }
  };

  const renderOpacitySlider = ({
    label,
    value,
    trackWidth,
    setTrackWidth,
    onPreview,
    onCommit,
  }) => {
    const percent = opacityToPercent(value);
    const ratio = opacityToRatio(value);
    const progressPercent = ratio * 100;

    let dragValue = snapOpacity(value);

    const valueFromEvent = (event) => opacityFromLocation(
      event.nativeEvent.locationX,
      trackWidth
    );

    const previewFromEvent = (event) => {
      dragValue = valueFromEvent(event);
      onPreview(dragValue);
    };

    const commitFromEvent = (event) => {
      dragValue = valueFromEvent(event);
      onCommit(dragValue);
    };

    const decreaseOpacity = () => {
      onCommit(snapOpacity(value - OPACITY_STEP));
    };

    const increaseOpacity = () => {
      onCommit(snapOpacity(value + OPACITY_STEP));
    };

    return (
      <View style={styles.opacitySliderBlock}>
        <View style={styles.opacitySliderHeader}>
          <Text style={styles.opacitySliderLabel}>{label}</Text>
          <Text style={styles.opacitySliderValue}>{percent}%</Text>
        </View>

        <View style={styles.opacitySliderControlRow}>
          <TouchableOpacity
            style={styles.opacityArrowButton}
            activeOpacity={0.82}
            onPress={decreaseOpacity}
          >
            <Text style={styles.opacityArrowButtonText}>‹</Text>
          </TouchableOpacity>

          <View
            style={styles.opacitySliderTrack}
            onLayout={(event) => setTrackWidth(event.nativeEvent.layout.width)}
            onStartShouldSetResponder={() => true}
            onMoveShouldSetResponder={() => true}
            onResponderGrant={previewFromEvent}
            onResponderMove={previewFromEvent}
            onResponderRelease={commitFromEvent}
            onResponderTerminate={() => onCommit(dragValue)}
            onResponderTerminationRequest={() => false}
          >
            <View
              pointerEvents="none"
              style={[styles.opacitySliderFill, { width: `${progressPercent}%` }]}
            />
            <View
              pointerEvents="none"
              style={[styles.opacitySliderThumb, { left: `${progressPercent}%` }]}
            />
          </View>

          <TouchableOpacity
            style={styles.opacityArrowButton}
            activeOpacity={0.82}
            onPress={increaseOpacity}
          >
            <Text style={styles.opacityArrowButtonText}>›</Text>
          </TouchableOpacity>
        </View>

        <View style={styles.opacitySliderRangeRow}>
          <Text style={styles.opacitySliderRangeText}>{OPACITY_MIN_PERCENT}%</Text>
          <Text style={styles.opacitySliderRangeText}>5% 단위</Text>
          <Text style={styles.opacitySliderRangeText}>{OPACITY_MAX_PERCENT}%</Text>
        </View>
      </View>
    );
  };

  const copyDebugLogs = async () => {
    const nativeModule = getNativeModule();
    if (!nativeModule || typeof nativeModule.copyDebugLogs !== 'function') {
      Alert.alert('\ub124\uc774\ud2f0\ube0c \uc5f0\uacb0 \ud544\uc694', '\ub85c\uadf8 \ubcf5\uc0ac \uae30\ub2a5\uc740 \uc2e4\uc81c Android APK\uc5d0\uc11c \uc0ac\uc6a9\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4.');
      return;
    }
    try {
      await nativeModule.copyDebugLogs();
      await loadDebugLogs();
      Alert.alert('\ubcf5\uc0ac \uc644\ub8cc', 'CopyBridge \ub85c\uadf8\ub97c \ud074\ub9bd\ubcf4\ub4dc\uc5d0 \ubcf5\uc0ac\ud588\uc2b5\ub2c8\ub2e4.');
    } catch (error) {
      Alert.alert('\ubcf5\uc0ac \uc2e4\ud328', 'CopyBridge \ub85c\uadf8\ub97c \ubcf5\uc0ac\ud558\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4.');
    }
  };

  const clearDebugLogs = async () => {
    const nativeModule = getNativeModule();
    if (!nativeModule || typeof nativeModule.clearDebugLogs !== 'function') {
      Alert.alert('\ub124\uc774\ud2f0\ube0c \uc5f0\uacb0 \ud544\uc694', '\ub85c\uadf8 \ube44\uc6b0\uae30 \uae30\ub2a5\uc740 \uc2e4\uc81c Android APK\uc5d0\uc11c \uc0ac\uc6a9\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4.');
      return;
    }
    try {
      await nativeModule.clearDebugLogs();
      await loadDebugLogs();
    } catch (error) {
      Alert.alert('\uc2e4\ud589 \uc2e4\ud328', 'CopyBridge \ub85c\uadf8\ub97c \ube44\uc6b0\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4.');
    }
  };

  useEffect(() => {
    loadDebugLogs();
    loadOpacitySettings();
    loadKeyboardShortcutSettings();
    loadDeepSeekSettings();
    loadApiUsageRecords();
  }, [loadDebugLogs, loadOpacitySettings, loadKeyboardShortcutSettings, loadDeepSeekSettings, loadApiUsageRecords]);

  useEffect(() => {
    if (!keyboardShortcutCaptureAction) return undefined;

    const intervalId = setInterval(() => {
      loadKeyboardShortcutSettings();
    }, 800);

    return () => clearInterval(intervalId);
  }, [keyboardShortcutCaptureAction, loadKeyboardShortcutSettings]);

  const showNextStepAlert = (label) => {
    Alert.alert(
      '다음 단계에서 연결',
      `${label} 기능은 Android 네이티브 연결 단계에서 구현합니다. 현재 화면은 기능 구조를 정리하는 준비 화면입니다.`
    );
  };

  const runNativeAction = async (label, actionName) => {
    const nativeModule = NativeModules.CopyBridgeNativeModule;

    if (Platform.OS !== 'android') {
      Alert.alert('Android 전용 기능', `${label} 기능은 Android에서만 사용할 수 있습니다.`);
      return;
    }

    if (!nativeModule || typeof nativeModule[actionName] !== 'function') {
      Alert.alert(
        '네이티브 연결 필요',
        `${label} 기능은 실제 Android APK에서 연결됩니다. Expo Go에서는 사용할 수 없습니다.`
      );
      return;
    }

    try {
      await nativeModule[actionName]();
      await loadDebugLogs();
    } catch (error) {
      Alert.alert('실행 실패', `${label} 기능을 실행하지 못했습니다.`);
    }
  };

  const changeApiUsageHour = (direction) => {
    setApiUsageHourOffset((current) => {
      const next = current + direction;
      if (next < 0) return 0;
      if (next > 23) return 23;
      return next;
    });
  };

  const renderApiUsageHourNav = () => (
    <View style={styles.apiUsageNavRow}>
      <TouchableOpacity
        style={styles.apiUsageNavButton}
        activeOpacity={0.8}
        onPress={() => changeApiUsageHour(1)}
      >
        <Text style={styles.apiUsageNavButtonText}>‹ 이전 시간</Text>
      </TouchableOpacity>

      <Text style={styles.apiUsageHourTitle}>
        {apiUsageHourOffset === 0 ? '현재 시간' : `${apiUsageHourOffset}시간 전`}
      </Text>

      <TouchableOpacity
        style={[
          styles.apiUsageNavButton,
          apiUsageHourOffset === 0 && styles.apiUsageNavButtonDisabled,
        ]}
        activeOpacity={0.8}
        disabled={apiUsageHourOffset === 0}
        onPress={() => changeApiUsageHour(-1)}
      >
        <Text style={styles.apiUsageNavButtonText}>다음 시간 ›</Text>
      </TouchableOpacity>
    </View>
  );

  const apiUsageHourRows = (() => {
    const now = Date.now();
    const currentHourStart = now - (now % API_USAGE_HOUR_MS);
    const hourStart = currentHourStart - apiUsageHourOffset * API_USAGE_HOUR_MS;

    const usageMap = new Map();
    const balanceMap = new Map();
    apiUsageRecords.forEach((item) => {
      usageMap.set(item.minuteStart, (usageMap.get(item.minuteStart) || 0) + item.usageUsd);
      if (item.balanceUsd !== null) {
        balanceMap.set(item.minuteStart, item.balanceUsd);
      }
    });

    return Array.from({ length: API_USAGE_ROWS_PER_HOUR }, (_, index) => {
      const minuteStart = hourStart + index * API_USAGE_MINUTE_MS;
      const date = new Date(minuteStart);
      const hour = String(date.getHours()).padStart(2, '0');
      const minute = String(date.getMinutes()).padStart(2, '0');

      return {
        key: String(minuteStart),
        timeLabel: `${hour}:${minute}`,
        usageUsd: usageMap.get(minuteStart) || 0,
        balanceUsd: balanceMap.has(minuteStart) ? balanceMap.get(minuteStart) : null,
      };
    });
  })();

  const apiUsageHourMaxUsage = Math.max(
    ...apiUsageHourRows.map((row) => row.usageUsd),
    0
  );

  const apiUsageHourTotalUsage = apiUsageHourRows.reduce(
    (sum, row) => sum + row.usageUsd,
    0
  );

  const apiUsageHourLastBalance = [...apiUsageHourRows]
    .reverse()
    .find((row) => row.balanceUsd !== null)?.balanceUsd ?? null;

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar style="dark" />

      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.container}
        showsVerticalScrollIndicator={false}
      >
        <View style={styles.header}>
          <Text style={styles.appName}>CopyBridge</Text>
          <Text style={styles.subtitle}>
            Telegram과 AI 앱 위에 떠 있는 복사·붙여넣기 보조 위젯
          </Text>
        </View>

        <View style={styles.card}>
          <Text style={styles.sectionTitle}>최종 사용 방식</Text>

          <View style={styles.flowBox}>
            <Text style={styles.flowText}>Telegram + AI 앱을 반반 화면으로 열기</Text>
            <Text style={styles.flowArrow}>↓</Text>
            <Text style={styles.flowText}>CopyBridge 플로팅 위젯을 화면 위에 띄우기</Text>
            <Text style={styles.flowArrow}>↓</Text>
            <Text style={styles.flowText}>위젯 버튼으로 대화 복사 / 답변 붙여넣기</Text>
          </View>
        </View>

        <View style={styles.previewCard}>
          <View style={styles.previewHeader}>
            <Text style={styles.sectionTitle}>플로팅 위젯 미리보기</Text>
            <Text style={styles.previewCaption}>앱 화면 위에 떠 있는 형태</Text>
          </View>

          <View style={styles.mockScreen}>
            <View style={styles.mockAppLeft}>
              <Text style={styles.mockAppLabel}>Telegram</Text>
              <View style={styles.mockBubbleWide} />
              <View style={styles.mockBubbleShort} />
              <View style={styles.mockBubbleMedium} />
            </View>

            <View style={styles.mockDivider} />

            <View style={styles.mockAppRight}>
              <Text style={styles.mockAppLabel}>AI App</Text>
              <View style={styles.mockBubbleMedium} />
              <View style={styles.mockBubbleWide} />
              <View style={styles.mockBubbleShort} />
            </View>

            <View style={styles.floatingWidget}>
              <Text style={styles.widgetTitle}>Bridge</Text>

              <TouchableOpacity
                style={styles.widgetButton}
                activeOpacity={0.85}
                onPress={() => showNextStepAlert('TG → AI 복사')}
              >
                <Text style={styles.widgetButtonText}>TG → AI 복사</Text>
              </TouchableOpacity>

              <TouchableOpacity
                style={styles.widgetButton}
                activeOpacity={0.85}
                onPress={() => showNextStepAlert('AI → TG 붙여넣기')}
              >
                <Text style={styles.widgetButtonText}>AI → TG 붙여넣기</Text>
              </TouchableOpacity>
            </View>
          </View>

          <Text style={styles.previewNotice}>
            실제 구현 단계에서는 이 작은 패널이 Telegram/GPT 화면 위에 떠 있고,
            메인 앱 화면은 권한 설정과 위젯 시작 용도로만 사용됩니다.
            TG → AI 복사는 텔레그램 대화를 AI 앱에 보낼 때, AI → TG 붙여넣기는 AI 답변을 텔레그램 입력창에 넣을 때 사용합니다.
          </Text>
        </View>

        <View style={styles.card}>
          <Text style={styles.sectionTitle}>필요한 준비</Text>
          <View style={styles.permissionItem}>
            <Text style={styles.permissionNumber}>01</Text>
            <View style={styles.permissionTextWrap}>
              <Text style={styles.permissionTitle}>다른 앱 위에 표시 권한</Text>
              <Text style={styles.permissionDescription}>
                텔레그램과 AI 앱 위에 작은 CopyBridge 위젯을 띄우기 위해 필요합니다.
              </Text>
            </View>
          </View>

          <View style={styles.divider} />

          <View style={styles.permissionItem}>
            <Text style={styles.permissionNumber}>02</Text>
            <View style={styles.permissionTextWrap}>
              <Text style={styles.permissionTitle}>접근성 권한</Text>
              <Text style={styles.permissionDescription}>
                텔레그램 화면의 텍스트를 읽고 입력창에 붙여넣기 위해 필요합니다. 버튼을 누른 뒤 설정 화면에서 "설치된 앱" 또는 "다운로드한 앱" 항목의 CopyBridge를 찾아 사용으로 켜주세요.
              </Text>
            </View>
          </View>
        </View>

        <View style={styles.actions}>
          <TouchableOpacity
            style={styles.secondaryButton}
            activeOpacity={0.8}
            onPress={() => runNativeAction('오버레이 권한 설정', 'openOverlaySettings')}
          >
            <Text style={styles.secondaryButtonText}>오버레이 권한 설정</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.secondaryButton}
            activeOpacity={0.8}
            onPress={() => runNativeAction('접근성 권한 설정', 'openAccessibilitySettings')}
          >
            <Text style={styles.secondaryButtonText}>접근성 권한 설정</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.primaryButton}
            activeOpacity={0.8}
            onPress={() => runNativeAction('떠 있는 위젯 시작', 'startFloatingWidget')}
          >
            <Text style={styles.primaryButtonText}>떠 있는 위젯 시작</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.secondaryButton}
            activeOpacity={0.8}
            onPress={() => runNativeAction('떠 있는 위젯 끄기', 'stopFloatingWidget')}
          >
            <Text style={styles.secondaryButtonText}>떠 있는 위젯 끄기</Text>
          </TouchableOpacity>

          
        </View>

        <View style={styles.deepSeekCard}>
          <Text style={styles.sectionTitle}>DeepSeek API 잔액</Text>
          <Text style={styles.deepSeekDescription}>
            DeepSeek API Key를 저장하면 현재 잔액을 확인할 수 있습니다. Key는 로그에 표시하지 않습니다.
          </Text>

          <TextInput
            style={styles.deepSeekInput}
            value={deepSeekApiKey}
            onChangeText={setDeepSeekApiKey}
            placeholder="DeepSeek API Key 입력"
            placeholderTextColor="#9A9288"
            autoCapitalize="none"
            autoCorrect={false}
            secureTextEntry
          />

          <View style={styles.deepSeekButtonRow}>
            <TouchableOpacity
              style={styles.deepSeekButton}
              activeOpacity={0.8}
              onPress={saveDeepSeekApiKey}
            >
              <Text style={styles.deepSeekButtonText}>API Key 저장</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.deepSeekButton}
              activeOpacity={0.8}
              onPress={checkDeepSeekBalance}
            >
              <Text style={styles.deepSeekButtonText}>잔액 확인</Text>
            </TouchableOpacity>
          </View>

          <View style={styles.deepSeekStatusBox}>
            <Text style={styles.deepSeekStatusText}>{deepSeekBalanceStatus}</Text>
          </View>

          <Text style={styles.deepSeekKeyStatus}>Key 상태: {deepSeekKeyStatus}</Text>
        </View>

        <View style={styles.opacityCard}>
            <Text style={styles.sectionTitle}>위젯 투명도</Text>
            <Text style={styles.opacityDescription}>
              펼쳐진 위젯과 최소화 B 아이콘의 투명도를 10%~100% 범위에서 5% 단위로 조절합니다.
            </Text>

            {renderOpacitySlider({
              label: '전체 위젯 투명도',
              value: widgetOpacity,
              trackWidth: widgetOpacityTrackWidth,
              setTrackWidth: setWidgetOpacityTrackWidth,
              onPreview: previewWidgetOpacityValue,
              onCommit: setWidgetOpacityValue,
            })}

            {renderOpacitySlider({
              label: '최소화 아이콘 투명도',
              value: collapsedOpacity,
              trackWidth: collapsedOpacityTrackWidth,
              setTrackWidth: setCollapsedOpacityTrackWidth,
              onPreview: previewCollapsedOpacityValue,
              onCommit: setCollapsedOpacityValue,
            })}
          </View>

        <View style={styles.shortcutCard}>
            <Text style={styles.sectionTitle}>물리 키보드 단축키</Text>
            <Text style={styles.shortcutDescription}>
              플로팅 패널이 펼치져 있을 때만 작동합니다. B 최소화 상태에서는 단축키를 무시합니다.
            </Text>

            {renderKeyboardShortcutRow({
              title: 'GPT로 보내기',
              description: '텔레그램 내용을 GPT 입력창으로 보냅니다.',
              value: keyboardShortcutSettings.telegramToGptLabel,
              action: KEYBOARD_SHORTCUT_ACTION_TELEGRAM_TO_GPT,
            })}

            {renderKeyboardShortcutRow({
              title: '텔레그램으로 보내기',
              description: 'GPT 답변을 텔레그램 입력창으로 보냅니다.',
              value: keyboardShortcutSettings.gptToTelegramLabel,
              action: KEYBOARD_SHORTCUT_ACTION_GPT_TO_TELEGRAM,
            })}

            {keyboardShortcutMessage ? (
              <Text style={styles.shortcutMessage}>{keyboardShortcutMessage}</Text>
            ) : null}

            <View style={styles.shortcutActionRow}>
              <TouchableOpacity
                style={styles.shortcutResetButton}
                activeOpacity={0.82}
                onPress={resetKeyboardShortcuts}
              >
                <Text style={styles.shortcutResetButtonText}>기본값으로 초기화</Text>
              </TouchableOpacity>

              {keyboardShortcutCaptureAction ? (
                <TouchableOpacity
                  style={styles.shortcutCancelButton}
                  activeOpacity={0.82}
                  onPress={cancelKeyboardShortcutCapture}
                >
                  <Text style={styles.shortcutCancelButtonText}>취소</Text>
                </TouchableOpacity>
              ) : null}
            </View>
          </View>

          <View style={styles.logCard}>
          <View style={styles.logHeader}>
            <Text style={styles.sectionTitle}>테스트 로그</Text>
            <TouchableOpacity
              style={styles.logSmallButton}
              activeOpacity={0.8}
              onPress={loadDebugLogs}
            >
              <Text style={styles.logSmallButtonText}>새로고침</Text>
            </TouchableOpacity>
          </View>

          <Text style={styles.logDescription}>
            GPT/TG 전송 테스트 결과가 여기에 누적됩니다. 문제가 생기면 전체 로그 복사 후 전달하세요.
          </Text>

          <View style={styles.logBox}>
            <Text style={styles.logText}>
              {debugLogs.trim() ? debugLogs : '아직 \uae30\ub85d\ub41c \ub85c\uadf8\uac00 \uc5c6\uc2b5\ub2c8\ub2e4.'}
            </Text>
          </View>

          <View style={styles.logActions}>
            <TouchableOpacity
              style={styles.logActionButton}
              activeOpacity={0.8}
              onPress={copyDebugLogs}
            >
              <Text style={styles.logActionButtonText}>전체 로그 복사</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.logActionButton}
              activeOpacity={0.8}
              onPress={clearDebugLogs}
            >
              <Text style={styles.logActionButtonText}>로그 비우기</Text>
            </TouchableOpacity>
          </View>
        </View>

        <View style={styles.apiUsageCard}>
          <Text style={styles.sectionTitle}>API 1분 사용량</Text>
          <Text style={styles.apiUsageDescription}>
            최근 24시간 API 사용량을 1분 단위로 확인하는 영역입니다.
          </Text>

          {renderApiUsageHourNav()}

          <TouchableOpacity
            style={styles.apiUsageTopRefreshButton}
            activeOpacity={0.8}
            onPress={loadApiUsageRecords}
          >
            <Text style={styles.apiUsageRefreshButtonText}>사용량 새로고침</Text>
          </TouchableOpacity>

          <View style={styles.apiUsageListBox}>
            <View style={styles.apiUsageListHeader}>
              <Text style={styles.apiUsagePlaceholderText}>
                {apiUsageHourLastBalance === null
                  ? '잔액 -'
                  : `잔액 ${apiUsageHourLastBalance.toFixed(3)}`}
              </Text>
              <Text style={styles.apiUsageTotalText}>
                총 {apiUsageHourTotalUsage.toFixed(3)}
              </Text>
            </View>

            <View style={styles.apiUsageRowsBox}>
              {apiUsageHourRows.map((row) => {
                const ratio = apiUsageHourMaxUsage > 0 ? row.usageUsd / apiUsageHourMaxUsage : 0;
                const barWidth = `${Math.max(4, Math.round(ratio * 100))}%`;
                const isZero = row.usageUsd <= 0;
                return (
                  <View key={row.key} style={styles.apiUsageTextRow}>
                    <Text style={styles.apiUsageTimeText}>{row.timeLabel}</Text>
                    <View style={styles.apiUsageBarTrack}>
                      {isZero ? (
                        <View style={styles.apiUsageZeroDot} />
                      ) : (
                        <View style={[styles.apiUsageBarFill, { width: barWidth }]} />
                      )}
                    </View>
                    <Text style={styles.apiUsageAmountText}>{row.usageUsd.toFixed(3)}</Text>
                  </View>
                );
              })}
            </View>

            <TouchableOpacity
              style={styles.apiUsageRefreshButton}
              activeOpacity={0.8}
              onPress={loadApiUsageRecords}
            >
              <Text style={styles.apiUsageRefreshButtonText}>사용량 새로고침</Text>
            </TouchableOpacity>
          </View>

          <View style={styles.apiUsageBottomNavWrap}>
            {renderApiUsageHourNav()}
          </View>
        </View>

        <Text style={styles.notice}>
          실제 대화 복사 / 답변 붙여넣기 버튼은 메인 화면이 아니라 플로팅 위젯 안에 들어갑니다.
        </Text>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#F7F4EF',
  },
  scroll: {
    flex: 1,
  },
  container: {
    paddingHorizontal: 24,
    paddingTop: 56,
    paddingBottom: 28,
  },
  header: {
    marginBottom: 24,
  },
  appName: {
    fontSize: 34,
    fontWeight: '800',
    color: '#171717',
    letterSpacing: -0.5,
  },
  subtitle: {
    marginTop: 10,
    fontSize: 16,
    lineHeight: 23,
    color: '#5F5A52',
  },
  card: {
    backgroundColor: '#FFFFFF',
    borderRadius: 24,
    padding: 22,
    marginBottom: 18,
    shadowColor: '#000000',
    shadowOpacity: 0.08,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 8 },
    elevation: 3,
  },
  previewCard: {
    backgroundColor: '#FFFFFF',
    borderRadius: 24,
    padding: 18,
    marginBottom: 18,
    shadowColor: '#000000',
    shadowOpacity: 0.08,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 8 },
    elevation: 3,
  },
  previewHeader: {
    marginBottom: 14,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: '#222222',
    marginBottom: 18,
  },
  previewCaption: {
    marginTop: -12,
    fontSize: 13,
    lineHeight: 18,
    color: '#817A70',
  },
  flowBox: {
    gap: 8,
  },
  flowText: {
    fontSize: 15,
    lineHeight: 22,
    color: '#4E4A44',
    fontWeight: '600',
  },
  flowArrow: {
    fontSize: 16,
    color: '#9A8D7C',
    marginLeft: 4,
  },
  mockScreen: {
    height: 220,
    borderRadius: 22,
    backgroundColor: '#EEE7DC',
    borderWidth: 1,
    borderColor: '#DED6CB',
    overflow: 'hidden',
    flexDirection: 'row',
    position: 'relative',
  },
  mockAppLeft: {
    flex: 1,
    backgroundColor: '#F9F7F3',
    padding: 14,
  },
  mockAppRight: {
    flex: 1,
    backgroundColor: '#F3EFE8',
    padding: 14,
  },
  mockDivider: {
    width: 1,
    backgroundColor: '#DED6CB',
  },
  mockAppLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#817A70',
    marginBottom: 16,
  },
  mockBubbleWide: {
    width: '86%',
    height: 22,
    borderRadius: 11,
    backgroundColor: '#D8CDBF',
    marginBottom: 12,
  },
  mockBubbleMedium: {
    width: '68%',
    height: 22,
    borderRadius: 11,
    backgroundColor: '#D8CDBF',
    marginBottom: 12,
  },
  mockBubbleShort: {
    width: '48%',
    height: 22,
    borderRadius: 11,
    backgroundColor: '#D8CDBF',
    marginBottom: 12,
  },
  floatingWidget: {
    position: 'absolute',
    right: 14,
    top: 58,
    width: 108,
    borderRadius: 18,
    backgroundColor: '#171717',
    padding: 8,
    shadowColor: '#000000',
    shadowOpacity: 0.22,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 10 },
    elevation: 8,
  },
  widgetTitle: {
    fontSize: 10,
    fontWeight: '800',
    color: '#FFFFFF',
    marginBottom: 6,
    textAlign: 'center',
  },
  widgetButton: {
    minHeight: 30,
    borderRadius: 10,
    backgroundColor: '#FFFFFF',
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 6,
    marginBottom: 6,
  },
  widgetButtonText: {
    fontSize: 10,
    fontWeight: '800',
    color: '#171717',
  },
  previewNotice: {
    marginTop: 14,
    fontSize: 13,
    lineHeight: 19,
    color: '#817A70',
  },
  permissionItem: {
    flexDirection: 'row',
    alignItems: 'flex-start',
  },
  permissionNumber: {
    width: 38,
    fontSize: 14,
    fontWeight: '800',
    color: '#8B735A',
  },
  permissionTextWrap: {
    flex: 1,
  },
  permissionTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: '#222222',
  },
  permissionDescription: {
    marginTop: 6,
    fontSize: 14,
    lineHeight: 20,
    color: '#6B665E',
  },
  divider: {
    height: 1,
    backgroundColor: '#ECE6DD',
    marginVertical: 18,
  },
  actions: {
    marginTop: 4,
    marginBottom: 18,
    gap: 12,
  },
  primaryButton: {
    height: 54,
    borderRadius: 16,
    backgroundColor: '#171717',
    alignItems: 'center',
    justifyContent: 'center',
  },
  primaryButtonText: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '700',
  },
  secondaryButton: {
    height: 54,
    borderRadius: 16,
    backgroundColor: '#FFFFFF',
    borderWidth: 1,
    borderColor: '#DED6CB',
    alignItems: 'center',
    justifyContent: 'center',
  },
  secondaryButtonText: {
    color: '#222222',
    fontSize: 16,
    fontWeight: '700',
  },
  deepSeekCard: {
    backgroundColor: '#FFFFFF',
    borderRadius: 24,
    padding: 18,
    marginBottom: 18,
    shadowColor: '#000000',
    shadowOpacity: 0.08,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 8 },
    elevation: 3,
  },
  deepSeekDescription: {
    marginTop: -8,
    marginBottom: 14,
    fontSize: 13,
    lineHeight: 19,
    color: '#817A70',
  },
  deepSeekInput: {
    height: 48,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#DED6CB',
    backgroundColor: '#F9F7F3',
    paddingHorizontal: 14,
    fontSize: 14,
    color: '#222222',
    marginBottom: 12,
  },
  deepSeekButtonRow: {
    flexDirection: 'row',
    gap: 10,
    marginBottom: 12,
  },
  deepSeekButton: {
    flex: 1,
    height: 46,
    borderRadius: 14,
    backgroundColor: '#171717',
    alignItems: 'center',
    justifyContent: 'center',
  },
  deepSeekButtonText: {
    color: '#FFFFFF',
    fontSize: 14,
    fontWeight: '700',
  },
  deepSeekStatusBox: {
    height: 46,
    borderRadius: 14,
    backgroundColor: '#171717',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 10,
  },
  deepSeekStatusText: {
    color: '#FACC15',
    fontSize: 20,
    fontWeight: '800',
  },
  deepSeekKeyStatus: {
    fontSize: 12,
    lineHeight: 17,
    color: '#817A70',
  },
  opacityCard: {
    backgroundColor: '#FFFFFF',
    borderRadius: 24,
    padding: 18,
    marginBottom: 18,
    shadowColor: '#000000',
    shadowOpacity: 0.08,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 8 },
    elevation: 3,
  },
  opacityDescription: {
    marginTop: -8,
    marginBottom: 16,
    fontSize: 13,
    lineHeight: 19,
    color: '#817A70',
  },
  opacityLabel: {
    marginTop: 10,
    marginBottom: 8,
    fontSize: 13,
    fontWeight: '700',
    color: '#2A2723',
  },
  opacityOptions: {
    flexDirection: 'row',
    gap: 8,
  },
  opacityOption: {
    flex: 1,
    backgroundColor: '#F3F4F6',
    borderRadius: 12,
    paddingVertical: 10,
    alignItems: 'center',
  },
  opacityOptionActive: {
    backgroundColor: '#111111',
  },
  opacityOptionText: {
    fontSize: 13,
    fontWeight: '700',
    color: '#555555',
  },
  opacityOptionTextActive: {
    color: '#FFFFFF',
  },
  opacitySliderBlock: {
    marginTop: 10,
    marginBottom: 16,
  },
  opacitySliderHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 10,
  },
  opacitySliderLabel: {
    fontSize: 13,
    fontWeight: '700',
    color: '#2A2723',
  },
  opacitySliderValue: {
    minWidth: 52,
    textAlign: 'right',
    fontSize: 14,
    fontWeight: '800',
    color: '#171717',
  },
  opacitySliderControlRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  opacityArrowButton: {
    width: 38,
    height: 38,
    borderRadius: 19,
    backgroundColor: '#171717',
    alignItems: 'center',
    justifyContent: 'center',
  },
  opacityArrowButtonText: {
    color: '#FFFFFF',
    fontSize: 28,
    fontWeight: '800',
    lineHeight: 30,
  },
  opacitySliderTrack: {
    flex: 1,
    height: 34,
    borderRadius: 17,
    backgroundColor: '#ECE6DD',
    justifyContent: 'center',
    position: 'relative',
    overflow: 'visible',
  },
  opacitySliderFill: {
    position: 'absolute',
    left: 0,
    height: 30,
    borderRadius: 15,
    backgroundColor: '#171717',
  },
  opacitySliderThumb: {
    position: 'absolute',
    width: 24,
    height: 24,
    marginLeft: -12,
    borderRadius: 12,
    backgroundColor: '#FFFFFF',
    borderWidth: 3,
    borderColor: '#171717',
    shadowColor: '#000000',
    shadowOpacity: 0.18,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 3 },
    elevation: 3,
  },
  opacitySliderRangeRow: {
    marginTop: 7,
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  opacitySliderRangeText: {
    fontSize: 11,
    fontWeight: '700',
    color: '#8B8176',
  },
  shortcutCard: {
    backgroundColor: '#FFFFFF',
    borderRadius: 24,
    padding: 18,
    marginBottom: 18,
    shadowColor: '#000000',
    shadowOpacity: 0.08,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 8 },
    elevation: 3,
  },
  shortcutDescription: {
    marginTop: -8,
    marginBottom: 14,
    fontSize: 13,
    lineHeight: 19,
    color: '#817A70',
  },
  shortcutRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 12,
    borderTopWidth: 1,
    borderTopColor: '#ECE6DD',
  },
  shortcutTextBlock: {
    flex: 1,
  },
  shortcutTitle: {
    fontSize: 14,
    fontWeight: '800',
    color: '#222222',
  },
  shortcutSubtext: {
    marginTop: 4,
    fontSize: 12,
    lineHeight: 17,
    color: '#817A70',
  },
  shortcutValue: {
    marginTop: 8,
    alignSelf: 'flex-start',
    borderRadius: 12,
    backgroundColor: '#F3F0EA',
    paddingHorizontal: 10,
    paddingVertical: 6,
    fontSize: 13,
    fontWeight: '800',
    color: '#171717',
  },
  shortcutChangeButton: {
    minWidth: 78,
    height: 42,
    borderRadius: 14,
    backgroundColor: '#171717',
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 12,
  },
  shortcutChangeButtonActive: {
    backgroundColor: '#8B735A',
  },
  shortcutChangeButtonText: {
    color: '#FFFFFF',
    fontSize: 13,
    fontWeight: '800',
  },
  shortcutChangeButtonTextActive: {
    color: '#FFFFFF',
  },
  shortcutMessage: {
    marginTop: 6,
    fontSize: 12,
    lineHeight: 18,
    color: '#6B665E',
    fontWeight: '700',
  },
  shortcutActionRow: {
    flexDirection: 'row',
    gap: 10,
    marginTop: 12,
  },
  shortcutResetButton: {
    flex: 1,
    height: 44,
    borderRadius: 14,
    backgroundColor: '#F3F0EA',
    alignItems: 'center',
    justifyContent: 'center',
  },
  shortcutResetButtonText: {
    color: '#222222',
    fontSize: 13,
    fontWeight: '800',
  },
  shortcutCancelButton: {
    width: 72,
    height: 44,
    borderRadius: 14,
    backgroundColor: '#333333',
    alignItems: 'center',
    justifyContent: 'center',
  },
  shortcutCancelButtonText: {
    color: '#FFFFFF',
    fontSize: 13,
    fontWeight: '800',
  },
  logCard: {
    backgroundColor: '#FFFFFF',
    borderRadius: 24,
    padding: 18,
    marginBottom: 18,
    shadowColor: '#000000',
    shadowOpacity: 0.08,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 8 },
    elevation: 3,
  },
  logHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
  },
  logSmallButton: {
    backgroundColor: '#333333',
    borderRadius: 14,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  logSmallButtonText: {
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: '700',
  },
  logDescription: {
    marginTop: -8,
    marginBottom: 12,
    fontSize: 13,
    lineHeight: 19,
    color: '#817A70',
  },
  logBox: {
    minHeight: 160,
    maxHeight: 260,
    backgroundColor: '#171717',
    borderRadius: 14,
    padding: 12,
  },
  logText: {
    color: '#F7F4EF',
    fontSize: 11,
    lineHeight: 16,
  },
  logActions: {
    flexDirection: 'row',
    gap: 10,
    marginTop: 12,
  },
  logActionButton: {
    flex: 1,
    backgroundColor: '#333333',
    borderRadius: 14,
    paddingVertical: 12,
    alignItems: 'center',
  },
  logActionButtonText: {
    color: '#FFFFFF',
    fontSize: 13,
    fontWeight: '700',
  },
  apiUsageCard: {
    backgroundColor: '#FFFFFF',
    borderRadius: 24,
    padding: 18,
    marginBottom: 18,
    shadowColor: '#000000',
    shadowOpacity: 0.08,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 8 },
    elevation: 3,
  },
  apiUsageDescription: {
    marginTop: -8,
    marginBottom: 12,
    fontSize: 13,
    lineHeight: 19,
    color: '#817A70',
  },
  apiUsageNavRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    marginBottom: 10,
  },
  apiUsageNavButton: {
    flex: 1,
    backgroundColor: '#333333',
    borderRadius: 12,
    paddingVertical: 8,
    alignItems: 'center',
  },
  apiUsageNavButtonDisabled: {
    opacity: 0.35,
  },
  apiUsageNavButtonText: {
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: '700',
  },
  apiUsageHourTitle: {
    minWidth: 74,
    textAlign: 'center',
    fontSize: 13,
    fontWeight: '800',
    color: '#222222',
  },
  apiUsagePlaceholderBox: {
    minHeight: 96,
    borderRadius: 14,
    backgroundColor: '#171717',
    alignItems: 'center',
    justifyContent: 'center',
  },
  apiUsagePlaceholderText: {
    color: '#F7F4EF',
    fontSize: 13,
    fontWeight: '700',
  },
  apiUsageListBox: {
    borderRadius: 14,
    backgroundColor: '#171717',
    padding: 12,
  },
  apiUsageListHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 10,
  },
  apiUsageTotalText: {
    color: '#FACC15',
    fontSize: 12,
    fontWeight: '800',
    textAlign: 'right',
    fontVariant: ['tabular-nums'],
  },
  apiUsageBottomNavWrap: {
    marginTop: 12,
  },
  apiUsageTopRefreshButton: {
    marginTop: 2,
    marginBottom: 12,
    backgroundColor: '#333333',
    borderRadius: 14,
    paddingHorizontal: 16,
    paddingVertical: 10,
    alignItems: 'center',
  },
  apiUsageRowsBox: {
    marginTop: 10,
    marginBottom: 10,
  },
  apiUsageTextRow: {
    height: 16,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  apiUsageTimeText: {
    width: 48,
    fontSize: 10,
    color: '#D6D3CC',
    fontVariant: ['tabular-nums'],
  },
  apiUsageBarTrack: {
    flex: 1,
    height: 6,
    justifyContent: 'center',
    alignItems: 'flex-start',
    marginHorizontal: 8,
  },
  apiUsageBarFill: {
    height: 5,
    minWidth: 4,
    borderRadius: 3,
    backgroundColor: '#FACC15',
  },
  apiUsageZeroDot: {
    width: 4,
    height: 4,
    borderRadius: 2,
    backgroundColor: '#6B7280',
  },
  apiUsageAmountText: {
    width: 54,
    textAlign: 'right',
    fontSize: 10,
    color: '#F7F4EF',
    fontVariant: ['tabular-nums'],
  },
  apiUsageRefreshButton: {
    marginTop: 12,
    backgroundColor: '#333333',
    borderRadius: 14,
    paddingHorizontal: 16,
    paddingVertical: 10,
    alignItems: 'center',
  },
  apiUsageRefreshButtonText: {
    color: '#FFFFFF',
    fontSize: 13,
    fontWeight: '700',
  },
  notice: {
    marginTop: 18,
    fontSize: 13,
    lineHeight: 19,
    color: '#817A70',
    textAlign: 'center',
  },
});
