import React, { useState, useEffect } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Alert, PermissionsAndroid, Platform } from 'react-native';
import Animated, { useAnimatedStyle, withTiming } from 'react-native-reanimated';
import DualCameraEngine, { PhysicalLens } from './specs/NativeDualCameraEngine';
import DualCameraView from './specs/DualCameraViewNativeComponent';

export default function CameraHUD() {
  const [lenses, setLenses] = useState<PhysicalLens[]>([]);
  const [lens16_9, setLens16_9] = useState<string | null>(null);
  const [lens9_16, setLens9_16] = useState<string | null>(null);
  const [isRecording, setIsRecording] = useState(false);
  const [layoutMode, setLayoutMode] = useState<'pip' | 'split'>('pip');
  const [isFallback, setIsFallback] = useState(false);
  const [outputDir, setOutputDir] = useState<string | null>(null);
  const [permissionsGranted, setPermissionsGranted] = useState(false);

  useEffect(() => {
    async function requestPermissionsAndSetup() {
      try {
        if (Platform.OS === 'android') {
          const permissionsToRequest = [
            PermissionsAndroid.PERMISSIONS.CAMERA,
            PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
          ];

          if (Platform.Version < 29) {
            permissionsToRequest.push(PermissionsAndroid.PERMISSIONS.WRITE_EXTERNAL_STORAGE);
          }

          const granted = await PermissionsAndroid.requestMultiple(permissionsToRequest);
          const allGranted = Object.values(granted).every(
            (status) => status === PermissionsAndroid.RESULTS.GRANTED
          );

          if (!allGranted) {
            Alert.alert(
              'Permissões Necessárias',
              'O aplicativo precisa de permissões de Câmera e Microfone para funcionar corretamente.'
            );
            return;
          }
          setPermissionsGranted(true);
        }

        const availableLenses = await DualCameraEngine.getAvailablePhysicalLenses();

        if (availableLenses && availableLenses.length > 0) {
          setLenses(availableLenses);
          setLens16_9(availableLenses[0].id);
          if (availableLenses.length > 1) {
            setLens9_16(availableLenses[1].id);
          } else {
             setLens9_16(availableLenses[0].id);
          }
          setIsFallback(false);
        } else {
          const fallbackLens = { id: '0', focalLength: 2.5, label: '1x (Principal)' };
          setLenses([fallbackLens]);
          setLens16_9(fallbackLens.id);
          setLens9_16(fallbackLens.id);
          setIsFallback(true);
        }

        let savedDir = await DualCameraEngine.getSavedOutputDirectory();
        if (!savedDir) {
           // Solicita o diretório logo na inicialização se ainda não estiver configurado
           savedDir = await DualCameraEngine.selectOutputDirectory();
        }
        setOutputDir(savedDir);
      } catch (e) {
        console.error("Error during initial setup:", e);
      }
    }
    requestPermissionsAndSetup();
  }, []);

  const handleRecord = async () => {
    if (!permissionsGranted) {
        Alert.alert('Erro', 'Permissões não concedidas.');
        return;
    }
    if (isRecording) {
      await DualCameraEngine.stopRecording();
      setIsRecording(false);
      Alert.alert('Gravação Finalizada', 'Os arquivos foram salvos com sucesso.');
    } else {
      if (!outputDir) {
         const newDir = await DualCameraEngine.selectOutputDirectory();
         if (newDir) {
             setOutputDir(newDir);
         } else {
             Alert.alert('Aviso', 'Selecione um diretório para salvar o vídeo.');
             return;
         }
      }
      await DualCameraEngine.startRecording();
      setIsRecording(true);
    }
  };

  const handleSelectDirectory = async () => {
    try {
      const newDir = await DualCameraEngine.selectOutputDirectory();
      if (newDir) {
        setOutputDir(newDir);
        Alert.alert('Diretório Salvo', 'As próximas gravações serão salvas aqui.');
      }
    } catch (e) {
      console.error("Error selecting directory:", e);
      Alert.alert('Erro', 'Falha ao selecionar o diretório.');
    }
  };

  const style16_9 = useAnimatedStyle(() => {
    if (layoutMode === 'split') {
      return {
        top: withTiming(0),
        height: withTiming('50%'),
        width: withTiming('100%'),
        zIndex: 1,
      };
    } else {
      return {
        top: withTiming(0),
        height: withTiming('100%'),
        width: withTiming('100%'),
        zIndex: 1,
      };
    }
  });

  const style9_16 = useAnimatedStyle(() => {
    if (layoutMode === 'split') {
      return {
        top: withTiming('50%'),
        height: withTiming('50%'),
        width: withTiming('100%'),
        zIndex: 2,
        borderRadius: withTiming(0),
        right: withTiming(0),
      };
    } else {
      return {
        top: withTiming('5%'),
        height: withTiming(200),
        width: withTiming(120),
        zIndex: 2,
        borderRadius: withTiming(12),
        right: withTiming('5%'),
      };
    }
  });

  return (
    <View style={styles.container}>
      <Animated.View style={[styles.cameraWrapper, style16_9]}>
        <DualCameraView style={styles.camera} activeLensId={lens16_9 || undefined} isSecondary={false} />
      </Animated.View>
      <Animated.View style={[styles.cameraWrapper, style9_16, styles.shadow]}>
        <DualCameraView style={styles.camera} activeLensId={lens9_16 || undefined} isSecondary={true} />
      </Animated.View>

      <TouchableOpacity style={styles.settingsBtn} onPress={handleSelectDirectory}>
        <Text style={styles.settingsIcon}>⚙️</Text>
      </TouchableOpacity>

      <View style={styles.glassPanel}>
        <View style={styles.lensControls}>
          <Text style={styles.label}>16:9 Lens:</Text>
          {lenses.map(lens => (
            <TouchableOpacity
              key={`16_9_${lens.id}`}
              style={[styles.lensBtn, lens16_9 === lens.id && styles.lensBtnActive, !isFallback && lens9_16 === lens.id && styles.lensBtnDisabled]}
              disabled={!isFallback && lens9_16 === lens.id}
              onPress={() => setLens16_9(lens.id)}
            >
              <Text style={styles.lensText}>{lens.label}</Text>
            </TouchableOpacity>
          ))}
        </View>
        <View style={styles.lensControls}>
          <Text style={styles.label}>9:16 Lens:</Text>
          {lenses.map(lens => (
            <TouchableOpacity
              key={`9_16_${lens.id}`}
              style={[styles.lensBtn, lens9_16 === lens.id && styles.lensBtnActive, !isFallback && lens16_9 === lens.id && styles.lensBtnDisabled]}
              disabled={!isFallback && lens16_9 === lens.id}
              onPress={() => setLens9_16(lens.id)}
            >
              <Text style={styles.lensText}>{lens.label}</Text>
            </TouchableOpacity>
          ))}
        </View>

        <View style={styles.actionRow}>
          <TouchableOpacity style={styles.layoutBtn} onPress={() => setLayoutMode(layoutMode === 'pip' ? 'split' : 'pip')}>
            <Text style={styles.layoutBtnText}>Toggle Layout</Text>
          </TouchableOpacity>

          <TouchableOpacity style={[styles.recordBtn, isRecording && styles.recordBtnActive]} onPress={handleRecord}>
            <View style={styles.recordInner} />
          </TouchableOpacity>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: 'black',
  },
  cameraWrapper: {
    position: 'absolute',
    overflow: 'hidden',
  },
  camera: {
    width: '100%',
    height: '100%',
  },
  shadow: {
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.5,
    shadowRadius: 5,
    elevation: 10,
  },
  settingsBtn: {
    position: 'absolute',
    top: 50,
    left: 20,
    backgroundColor: 'rgba(255, 255, 255, 0.2)',
    borderRadius: 20,
    width: 40,
    height: 40,
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: 20,
  },
  settingsIcon: {
    fontSize: 20,
  },
  glassPanel: {
    position: 'absolute',
    bottom: 0,
    width: '100%',
    backgroundColor: 'rgba(255, 255, 255, 0.15)',
    borderTopLeftRadius: 30,
    borderTopRightRadius: 30,
    padding: 20,
    paddingBottom: 40,
    zIndex: 10,
  },
  lensControls: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 10,
  },
  label: {
    color: 'white',
    marginRight: 10,
    fontWeight: 'bold',
    width: 80,
  },
  lensBtn: {
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 15,
    backgroundColor: 'rgba(0,0,0,0.4)',
    marginRight: 5,
  },
  lensBtnActive: {
    backgroundColor: '#007AFF',
  },
  lensBtnDisabled: {
    opacity: 0.3,
  },
  lensText: {
    color: 'white',
    fontSize: 12,
  },
  actionRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: 20,
  },
  layoutBtn: {
    backgroundColor: 'rgba(255,255,255,0.2)',
    padding: 10,
    borderRadius: 20,
  },
  layoutBtnText: {
    color: 'white',
  },
  recordBtn: {
    width: 60,
    height: 60,
    borderRadius: 30,
    borderWidth: 4,
    borderColor: 'white',
    justifyContent: 'center',
    alignItems: 'center',
  },
  recordBtnActive: {
    borderColor: 'red',
  },
  recordInner: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: 'red',
  }
});
