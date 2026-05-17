import { StatusBar } from 'expo-status-bar';
import {
  Alert,
  SafeAreaView,
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

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar style="dark" />

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
              텔레그램 화면의 텍스트를 읽고 입력창에 붙여넣기 위해 필요합니다.
            </Text>
          </View>
        </View>
      </View>

      <View style={styles.actions}>
        <TouchableOpacity
          style={styles.secondaryButton}
          activeOpacity={0.8}
          onPress={() => showNextStepAlert('오버레이 권한 설정')}
        >
          <Text style={styles.secondaryButtonText}>오버레이 권한 설정</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.secondaryButton}
          activeOpacity={0.8}
          onPress={() => showNextStepAlert('접근성 권한 설정')}
        >
          <Text style={styles.secondaryButtonText}>접근성 권한 설정</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.primaryButton}
          activeOpacity={0.8}
          onPress={() => showNextStepAlert('떠 있는 위젯 시작')}
        >
          <Text style={styles.primaryButtonText}>떠 있는 위젯 시작</Text>
        </TouchableOpacity>
      </View>

      <Text style={styles.notice}>
        실제 대화 복사 / 답변 붙여넣기 버튼은 메인 화면이 아니라 플로팅 위젯 안에 들어갑니다.
      </Text>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F7F4EF',
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
  sectionTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: '#222222',
    marginBottom: 18,
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
    marginTop: 'auto',
    fontSize: 13,
    lineHeight: 19,
    color: '#817A70',
    textAlign: 'center',
  },
});
