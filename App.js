import { StatusBar } from 'expo-status-bar';
import {
  Alert,
  NativeModules,
  Platform,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

export default function App() {
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
    } catch (error) {
      Alert.alert('실행 실패', `${label} 기능을 실행하지 못했습니다.`);
    }
  };

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
  notice: {
    marginTop: 18,
    fontSize: 13,
    lineHeight: 19,
    color: '#817A70',
    textAlign: 'center',
  },
});
