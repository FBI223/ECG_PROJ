import os
import sys
import gc
import signal
import glob
import numpy as np
import wfdb
import matplotlib.pyplot as plt

from random import randint, choice
from sklearn.model_selection import train_test_split
from sklearn.metrics import confusion_matrix, ConfusionMatrixDisplay, classification_report

import tensorflow as tf
import tensorflow.keras.backend as K
from tensorflow.keras.models import Model
from tensorflow.keras.layers import Input, Conv1D, MaxPooling1D, UpSampling1D, concatenate
from tensorflow.keras.callbacks import EarlyStopping, ModelCheckpoint, ReduceLROnPlateau

from scipy.interpolate import CubicSpline
from PyQt6 import QtWidgets, QtGui


# Preferowane odprowadzenia
# Preferowane leady
PREFERRED_LEADS = ["MLII", "II", "ECG1" ,"mlii","ii" , "ecg1" ]
# Ścieżki do baz danych
QTDB_PATH = "C:/Users/msztu/Documents/EKG4/qtdb/"
LUDB_PATH = "ludb/data/"
MODEL_PATH = "unet_ecg_fold5.h5"  # Model UNet
TARGET_FS = 500
WINDOW_SIZE = 2000
BAD_PATIENTS = [7, 34, 90,  95, 104, 111]
BAD_PATIENTS_II = []

# Mapowanie symboli na klasy (0=none, 1=P, 2=QRS, 3=T)
WAVE_MAP = {'p': 1, 'N': 2, 't': 3}  # 0 = none



if not os.path.exists(MODEL_PATH):
    raise FileNotFoundError(f"❌ Brak pliku modelu {MODEL_PATH}")
print("[INFO] Załadowano model UNet")
model = tf.keras.models.load_model(MODEL_PATH)







def list_available_leads():
    """Znajduje dostępne leady w plikach .hea i wypisuje debugowanie."""
    records = glob.glob(os.path.join(QTDB_PATH, "*.hea"))
    lead_info = {}

    print("[DEBUG] Sprawdzanie dostępnych leadów w bazie QTDB...")

    for record in records:
        record_name = os.path.splitext(os.path.basename(record))[0]
        try:
            record_info = wfdb.rdrecord(os.path.join(QTDB_PATH, record_name))
            lead_info[record_name] = record_info.sig_name
            print(f"[INFO] Rekord: {record_name}, Lead-y: {record_info.sig_name}")
        except Exception as e:
            print(f"[ERROR] Nie udało się wczytać {record_name}: {e}")

    return lead_info


def augment_signal(signal):
    """Dodaje realistyczne zakłócenia do sygnału EKG."""
    L = len(signal)

    # 1. Szum Gaussowski (małe zakłócenia elektryczne)
    noise = np.random.normal(0, 0.01, L)

    # 2. Dryft bazowy (symulacja oddychania, niskoczęstotliwościowy trend)
    drift = 0.05 * np.sin(np.linspace(0, 2 * np.pi, L))

    # 3. Zakłócenia od mikro skurczów mięśni (szybkie, losowe zmiany)
    muscle_noise = 0.02 * np.random.randn(L) * np.sin(np.linspace(0, 50 * np.pi, L))

    # 4. Zakłócenia elektryczne 50Hz (lekkie zakłócenia sieci elektrycznej)
    electric_noise = 0.01 * np.sin(2 * np.pi * 50 * np.linspace(0, 1, L))

    # Skalujemy każdą perturbację losowym współczynnikiem w zakresie 0.5x - 1.5x
    noise *= np.random.uniform(0.5, 1.5)
    drift *= np.random.uniform(0.5, 1.5)
    muscle_noise *= np.random.uniform(0.5, 1.5)
    electric_noise *= np.random.uniform(0.5, 1.5)

    # Sumujemy wszystkie zakłócenia razem
    augmented_signal = signal + noise + drift + muscle_noise + electric_noise

    return augmented_signal


def augment_signalv1(signal):
    """Dodaje różne realistyczne zakłócenia do sygnału EKG."""
    L = len(signal)

    # 1. Dodanie szumu Gaussowskiego (małe zakłócenia elektryczne)
    noise = np.random.normal(0, 0.01, L)

    # 2. Dryft bazowy (symulacja oddychania, niskoczęstotliwościowy trend)
    drift = 0.05 * np.sin(np.linspace(0, 2*np.pi, L))

    # 3. Zakłócenia od mikro skurczów mięśni (szybkie, losowe zmiany)
    muscle_noise = 0.02 * np.random.randn(L) * np.sin(np.linspace(0, 50*np.pi, L))

    # 4. Zakłócenia elektryczne 50Hz (lekkie zakłócenia sieci elektrycznej)
    electric_noise = 0.01 * np.sin(2 * np.pi * 50 * np.linspace(0, 1, L))

    # Wybieramy losowe zakłócenie
    perturbation = np.random.choice([0, 1, 2, 3])

    if perturbation == 0:
        augmented_signal = signal + noise
    elif perturbation == 1:
        augmented_signal = signal + drift
    elif perturbation == 2:
        augmented_signal = signal + muscle_noise
    else:
        augmented_signal = signal + electric_noise

    return augmented_signal


def resample_signal(signal, orig_fs=500, target_fs=500):
    if orig_fs == target_fs:
        return signal
    time_orig = np.linspace(0, len(signal) / orig_fs, num=len(signal))
    time_target = np.linspace(0, len(signal) / orig_fs, num=int(len(signal) * target_fs / orig_fs))
    interpolator = CubicSpline(time_orig, signal)
    return interpolator(time_target)


def cleanup_resources(signum, frame):
    print("🛑 Przerywanie... zwalniam pamięć!")
    K.clear_session()
    gc.collect()
    sys.exit(0)

def select_best_lead(record):
    if record.p_signal is None or not hasattr(record, 'sig_name'):
        return None
    for lead in PREFERRED_LEADS:
        if lead in record.sig_name:
            print(f"[DEBUG] Wybrano lead: {lead}")

            return record.p_signal[:, record.sig_name.index(lead)]
    print("[DEBUG] Żaden preferowany lead nie został znaleziony.")
    return None

def find_annotation_file(record_name):
    possible_files = glob.glob(os.path.join(LUDB_PATH, record_name + ".*"))
    for lead in PREFERRED_LEADS:
        for file in possible_files:
            if file.endswith(f".{lead}"):
                print(f"[DEBUG] Znaleziono plik adnotacji: {file}")
                return file
    print(f"[DEBUG] Brak pliku adnotacji dla rekordu {record_name}")
    return None


def load_ecg(record_name):
    """Wczytuje sygnał EKG i adnotacje dla danego rekordu."""
    annotation_file = find_annotation_file(record_name)
    if annotation_file is None:
        return None, None

    record_path = os.path.join(LUDB_PATH, record_name)
    record = wfdb.rdrecord(record_path)
    ext = annotation_file.split('.')[-1]
    annotation = wfdb.rdann(annotation_file[:-len(ext)-1], extension=ext)

    best_lead = None
    for lead in PREFERRED_LEADS:
        if lead in record.sig_name:
            best_lead = record.p_signal[:, record.sig_name.index(lead)]
            break

    if best_lead is None:
        return None, None

    return best_lead, annotation  # Zwracamy rzeczywisty sygnał, a nie cały obiekt `Record`



def load_all_records_ludb():
    data_list = []
    for record_id in range(1, 201):
        if record_id in BAD_PATIENTS_II:
            print(f"[DEBUG] Pomijam pacjenta {record_id}")
            continue
        record_name = str(record_id)
        rec, ann = load_ecg(record_name)
        if rec is not None and ann is not None:
            best_lead = rec  # Już jest numpy.ndarray
            if best_lead is not None:
                data_list.append((best_lead, ann))
    print(f"[DEBUG] Łącznie wczytano {len(data_list)} rekordów")
    return data_list

def create_label_array(signal, annotation):
    L = len(signal)
    labels = np.zeros(L, dtype=int)
    i = 0
    while i < len(annotation.symbol):
        # Szukamy sekwencji: '(' <symbol> ')'
        if (annotation.symbol[i] == '(' and
                i+2 < len(annotation.symbol) and
                annotation.symbol[i+2] == ')' and
                annotation.symbol[i+1] in WAVE_MAP):
            wave_class = WAVE_MAP[annotation.symbol[i+1]]
            start_idx = annotation.sample[i]
            end_idx   = annotation.sample[i+2]
            start_idx = max(0, start_idx)
            end_idx   = min(L-1, end_idx)
            labels[start_idx:end_idx+1] = wave_class
            i += 3
        else:
            i += 1
    binc = np.bincount(labels, minlength=4)
    print(f"[DEBUG] Rozkład etykiet: none={binc[0]}, P={binc[1]}, QRS={binc[2]}, T={binc[3]}")
    return labels

def generate_training_fragments(signal, labels, num_fragments=5):
    L = len(signal)
    start_min = 1000
    start_max = L - 1000 - WINDOW_SIZE
    if start_max <= start_min:
        return [], [], [], []

    X_segments = []
    Y_segments = []
    X_aug_segments = []
    Y_aug_segments = []

    for _ in range(num_fragments):
        start_idx = randint(start_min, start_max)
        end_idx = start_idx + WINDOW_SIZE

        X_seg = signal[start_idx:end_idx].copy()
        Y_seg = labels[start_idx:end_idx].copy()

        # Normalizacja
        X_seg = (X_seg - np.mean(X_seg)) / (np.std(X_seg) + 1e-8)

        # Tworzymy wersję z zakłóceniami
        X_aug = augment_signal(X_seg)

        X_segments.append(X_seg)
        Y_segments.append(Y_seg)

        X_aug_segments.append(X_aug)
        Y_aug_segments.append(Y_seg)  # Adnotacje pozostają bez zmian!

    return X_segments, Y_segments, X_aug_segments, Y_aug_segments


from tensorflow.keras.layers import ZeroPadding1D, Conv1DTranspose, BatchNormalization

def build_unet(input_length):
    inputs = Input(shape=(input_length, 1))

    # =================== Encoder ===================
    c1 = Conv1D(4, 9, padding="same", activation="relu")(inputs)
    c1 = BatchNormalization()(c1)
    c1 = Conv1D(4, 9, padding="same", activation="relu")(c1)
    c1 = BatchNormalization()(c1)
    p1 = MaxPooling1D(pool_size=2, padding="same")(c1)  # 1/2

    c2 = Conv1D(8, 9, padding="same", activation="relu")(p1)
    c2 = BatchNormalization()(c2)
    c2 = Conv1D(8, 9, padding="same", activation="relu")(c2)
    c2 = BatchNormalization()(c2)
    p2 = MaxPooling1D(pool_size=2, padding="same")(c2)  # 1/4

    c3 = Conv1D(16, 9, padding="same", activation="relu")(p2)
    c3 = BatchNormalization()(c3)
    c3 = Conv1D(16, 9, padding="same", activation="relu")(c3)
    c3 = BatchNormalization()(c3)
    p3 = MaxPooling1D(pool_size=2, padding="same")(c3)  # 1/8

    c4 = Conv1D(32, 9, padding="same", activation="relu")(p3)
    c4 = BatchNormalization()(c4)
    c4 = Conv1D(32, 9, padding="same", activation="relu")(c4)
    c4 = BatchNormalization()(c4)
    p4 = MaxPooling1D(pool_size=2, padding="same")(c4)  # 1/16

    c5 = Conv1D(64, 9, padding="same", activation="relu")(p4)
    c5 = BatchNormalization()(c5)
    c5 = Conv1D(64, 9, padding="same", activation="relu")(c5)
    c5 = BatchNormalization()(c5)

    # =================== Decoder ===================
    u4 = Conv1DTranspose(32, 8, strides=2, padding="same")(c5)
    if u4.shape[1] != c4.shape[1]:
        u4 = ZeroPadding1D((0, 1))(u4)  # Dopasowanie wymiarów
    u4 = concatenate([u4, c4])

    c6 = Conv1D(32, 9, padding="same", activation="relu")(u4)
    c6 = BatchNormalization()(c6)
    c6 = Conv1D(32, 9, padding="same", activation="relu")(c6)
    c6 = BatchNormalization()(c6)

    u3 = Conv1DTranspose(16, 8, strides=2, padding="same")(c6)
    if u3.shape[1] != c3.shape[1]:
        u3 = ZeroPadding1D((0, 1))(u3)
    u3 = concatenate([u3, c3])

    c7 = Conv1D(16, 9, padding="same", activation="relu")(u3)
    c7 = BatchNormalization()(c7)
    c7 = Conv1D(16, 9, padding="same", activation="relu")(c7)
    c7 = BatchNormalization()(c7)

    u2 = Conv1DTranspose(8, 8, strides=2, padding="same")(c7)
    if u2.shape[1] != c2.shape[1]:
        u2 = ZeroPadding1D((0, 1))(u2)
    u2 = concatenate([u2, c2])

    c8 = Conv1D(8, 9, padding="same", activation="relu")(u2)
    c8 = BatchNormalization()(c8)
    c8 = Conv1D(8, 9, padding="same", activation="relu")(c8)
    c8 = BatchNormalization()(c8)

    u1 = Conv1DTranspose(4, 8, strides=2, padding="same")(c8)
    if u1.shape[1] != c1.shape[1]:
        u1 = ZeroPadding1D((0, 1))(u1)
    u1 = concatenate([u1, c1])

    c9 = Conv1D(4, 9, padding="same", activation="relu")(u1)
    c9 = BatchNormalization()(c9)
    c9 = Conv1D(4, 9, padding="same", activation="relu")(c9)
    c9 = BatchNormalization()(c9)

    # =================== Output ===================
    outputs = Conv1D(4, 1, activation="softmax")(c9)

    model = Model(inputs, outputs)
    model.compile(
        optimizer="adam",
        loss="categorical_crossentropy",
        metrics=["accuracy", tf.keras.metrics.Precision(), tf.keras.metrics.Recall()]
    )

    print("[DEBUG] Model UNet zbudowany")
    return model


def plot_confusion_matrix_samples(model, X, Y_onehot):
    preds = model.predict(X)
    pred_labels = np.argmax(preds, axis=-1).flatten()
    true_labels = np.argmax(Y_onehot, axis=-1).flatten()

    cm = confusion_matrix(true_labels, pred_labels, labels=[0,1,2,3])
    disp = ConfusionMatrixDisplay(confusion_matrix=cm, display_labels=["none","P","QRS","T"])
    disp.plot(cmap=plt.cm.Blues)
    plt.title("Confusion Matrix (sample-level)")
    plt.show()

    print("\n[DEBUG] Classification Report (sample-level):")
    print(classification_report(true_labels, pred_labels,
                                labels=[0,1,2,3],
                                target_names=["none","P","QRS","T"]))


# ================== DODATKOWE FUNKCJE ONSET/OFFSET ==================
def extract_segments_from_prediction(pred_labels):
    segments = []
    current_class = pred_labels[0]
    start = 0
    for i in range(1, len(pred_labels)):
        if pred_labels[i] != current_class:
            if current_class != 0:
                segments.append((start, i - 1, current_class))
            current_class = pred_labels[i]
            start = i
    if current_class != 0:
        segments.append((start, len(pred_labels) - 1, current_class))
    return segments

def evaluate_onset_offset(true_segments, pred_segments, tolerance=150):
    used_pred = set()
    TP = 0
    for (true_start, true_end, true_class) in true_segments:
        found_match = False
        for j, (pred_start, pred_end, pred_class) in enumerate(pred_segments):
            if j in used_pred:
                continue
            if pred_class == true_class:
                onset_diff = abs(pred_start - true_start)
                offset_diff = abs(pred_end - true_end)
                if (onset_diff <= tolerance) and (offset_diff <= tolerance):
                    TP += 1
                    used_pred.add(j)
                    found_match = True
                    break
        # brak dopasowania => FN (liczony niżej)
    FP = len(pred_segments) - len(used_pred)
    FN = len(true_segments) - TP
    return TP, FP, FN

def evaluate_onset_offset_for_dataset(model, X, Y, tolerance=150):
    """
    Przechodzi po wszystkich fragmentach w X, Y.
    Dla każdego:
      - pred_labels = argmax(model.predict(X[i]))
      - true_labels = argmax(Y[i])
      - wyodrębnij segmenty, porównaj onset/offset
    Zwraca 3 liczby: sumaryczne TP, FP, FN.
    """
    preds = model.predict(X)
    total_TP = 0
    total_FP = 0
    total_FN = 0
    for i in range(len(X)):
        pred_labels = np.argmax(preds[i], axis=-1)  # (2000,)
        true_labels = np.argmax(Y[i], axis=-1)      # (2000,)

        pred_segments = extract_segments_from_prediction(pred_labels)
        true_segments = extract_segments_from_prediction(true_labels)

        TP, FP, FN = evaluate_onset_offset(true_segments, pred_segments, tolerance=tolerance)
        total_TP += TP
        total_FP += FP
        total_FN += FN

    return total_TP, total_FP, total_FN


def main():
    import numpy as np
    from sklearn.model_selection import KFold
    from tensorflow.keras.callbacks import EarlyStopping, ModelCheckpoint, ReduceLROnPlateau

    import signal
    signal.signal(signal.SIGINT, cleanup_resources)

    print("[DEBUG] Rozpoczynam wczytywanie rekordów...")
    all_data = load_all_records_ludb()
    print(f"[DEBUG] Załadowano {len(all_data)} rekordów.")

    X_fragments = []
    Y_fragments = []
    X_aug_fragments = []
    Y_aug_fragments = []

    for idx, (signal_ecg, ann) in enumerate(all_data):
        print(f"[DEBUG] Procesuję pacjenta idx={idx}, sygnał shape={signal_ecg.shape}")
        labels_full = create_label_array(signal_ecg, ann)
        X_segs, Y_segs, X_aug_segs, Y_aug_segs = generate_training_fragments(signal_ecg, labels_full, num_fragments=10)
        print(f"[DEBUG] Pacjent idx={idx}: wygenerowano {len(X_segs)} fragmentów + {len(X_aug_segs)} augmentowanych")
        X_fragments.extend(X_segs)
        Y_fragments.extend(Y_segs)
        X_aug_fragments.extend(X_aug_segs)
        Y_aug_fragments.extend(Y_aug_segs)

    X_total = np.array(X_fragments + X_aug_fragments, dtype=np.float32).reshape(-1, WINDOW_SIZE, 1)
    Y_total = np.array(Y_fragments + Y_aug_fragments, dtype=np.int32)
    print(f"[DEBUG] Łącznie fragmentów: {len(X_total)} (oryginalne + augmentowane)")

    # One-hot encoding etykiet
    Y_onehot = np.zeros((len(Y_total), WINDOW_SIZE, 4), dtype=np.float32)
    for i in range(len(Y_total)):
        Y_onehot[i, np.arange(WINDOW_SIZE), Y_total[i]] = 1.0

    # K-Fold Cross Validation
    kfold = KFold(n_splits=5, shuffle=True, random_state=42)
    fold_results = []
    fold = 1

    for train_idx, val_idx in kfold.split(X_total):
        print(f"\n[DEBUG] Rozpoczynam fold {fold}")
        X_train, X_val = X_total[train_idx], X_total[val_idx]
        y_train, y_val = Y_onehot[train_idx], Y_onehot[val_idx]

        model = build_unet(WINDOW_SIZE)
        callbacks = [
            EarlyStopping(monitor='val_loss', patience=5, restore_best_weights=True, verbose=1),
            ModelCheckpoint(f"unet_ecg_fold{fold}.h5", monitor='val_loss', save_best_only=True, verbose=1),
            ReduceLROnPlateau(monitor='val_loss', factor=0.5, patience=2, verbose=1)
        ]

        history = model.fit(
            X_train, y_train,
            validation_data=(X_val, y_val),
            epochs=20,
            batch_size=64,
            callbacks=callbacks,
            verbose=1
        )

        # Ocena na zbiorze walidacyjnym
        results = model.evaluate(X_val, y_val, verbose=0)
        # Zakładając, że model.compile(...) ma kolejno: loss, accuracy, Precision, Recall
        loss, accuracy, precision, recall = results[0], results[1], results[2], results[3]
        print(f"[DEBUG] Fold {fold} - Val Loss: {loss:.4f}, Accuracy: {accuracy:.4f}, Precision: {precision:.4f}, Recall: {recall:.4f}")
        fold_results.append((loss, accuracy, precision, recall))
        fold += 1

    # Uśrednienie wyników z k-fold
    avg_loss = np.mean([res[0] for res in fold_results])
    avg_acc = np.mean([res[1] for res in fold_results])
    avg_prec = np.mean([res[2] for res in fold_results])
    avg_rec = np.mean([res[3] for res in fold_results])

    print("\n[DEBUG] Średnie wyniki walidacji:")
    print(f"Loss:      {avg_loss:.4f}")
    print(f"Accuracy:  {avg_acc:.4f}")
    print(f"Precision: {avg_prec:.4f}")
    print(f"Recall:    {avg_rec:.4f}")

    # Opcjonalnie: Wyświetlenie macierzy konfuzji i raportu klasyfikacji na ostatnim foldzie
    print("\n[DEBUG] Ewaluacja onset/offset na ostatnim foldzie (z tolerancją 150):")
    plot_confusion_matrix_samples(model, X_val, y_val)
    total_TP, total_FP, total_FN = evaluate_onset_offset_for_dataset(model, X_val, y_val, tolerance=150)
    precision_metric = total_TP / (total_TP + total_FP + 1e-9)
    recall_metric = total_TP / (total_TP + total_FN + 1e-9)
    f1 = 2 * precision_metric * recall_metric / (precision_metric + recall_metric + 1e-9)
    print(f"TP: {total_TP}, FP: {total_FP}, FN: {total_FN}")
    print(f"Precision: {precision_metric:.4f}, Recall: {recall_metric:.4f}, F1: {f1:.4f}")




def load_test_data(num_samples=100):
    """Losuje `num_samples` pacjentów i zwraca sygnał EKG + adnotacje."""
    available_patients = [str(i) for i in range(1, 201) if i not in BAD_PATIENTS_II]
    selected_patients = np.random.choice(available_patients, num_samples, replace=False)

    X_test, Y_test, records = [], [], []
    for record_name in selected_patients:
        signal, annotation = load_ecg(record_name)
        if signal is None or annotation is None:
            continue

        labels = create_label_array(signal, annotation)

        # Pobranie fragmentu o długości WINDOW_SIZE
        if len(signal) > WINDOW_SIZE:
            start_idx = np.random.randint(0, len(signal) - WINDOW_SIZE)
            X_segment = signal[start_idx:start_idx + WINDOW_SIZE].copy()
            Y_segment = labels[start_idx:start_idx + WINDOW_SIZE].copy()

            # Normalizacja
            X_segment = (X_segment - np.mean(X_segment)) / (np.std(X_segment) + 1e-8)

            X_test.append(X_segment)
            Y_test.append(Y_segment)
            records.append(record_name)

    X_test = np.array(X_test, dtype=np.float32).reshape(-1, WINDOW_SIZE, 1)
    Y_test = np.array(Y_test, dtype=np.int32)

    return X_test, Y_test, records



def plot_predictions(X_test, Y_test, pred_labels, records):
    """Tworzy dwa wykresy: jeden dla rzeczywistych adnotacji, drugi dla predykcji."""
    os.makedirs("trained_models/v4_sota/predictions", exist_ok=True)

    for i in range(len(X_test)):
        fig, axs = plt.subplots(2, 1, figsize=(12, 8), sharex=True)

        # Górny wykres - rzeczywiste adnotacje
        axs[0].plot(X_test[i], label="Sygnał EKG", color="black", linewidth=1)
        axs[0].set_title(f"Pacjent {records[i]} - Oryginalne adnotacje (GT)")
        axs[0].set_ylabel("Amplituda")

        true_p = np.where(Y_test[i] == 1)[0]
        true_qrs = np.where(Y_test[i] == 2)[0]
        true_t = np.where(Y_test[i] == 3)[0]

        axs[0].scatter(true_p, X_test[i][true_p], color="blue", label="GT: P", marker="o", s=40)
        axs[0].scatter(true_qrs, X_test[i][true_qrs], color="red", label="GT: QRS", marker="o", s=40)
        axs[0].scatter(true_t, X_test[i][true_t], color="green", label="GT: T", marker="o", s=40)
        axs[0].legend()
        axs[0].grid()

        # Dolny wykres - predykcja modelu
        axs[1].plot(X_test[i], label="Sygnał EKG", color="black", linewidth=1)
        axs[1].set_title(f"Pacjent {records[i]} - Predykcja modelu")
        axs[1].set_xlabel("Próbki")
        axs[1].set_ylabel("Amplituda")

        pred_p = np.where(pred_labels[i] == 1)[0]
        pred_qrs = np.where(pred_labels[i] == 2)[0]
        pred_t = np.where(pred_labels[i] == 3)[0]

        axs[1].scatter(pred_p, X_test[i][pred_p], color="blue", label="Pred: P", marker="x", s=30)
        axs[1].scatter(pred_qrs, X_test[i][pred_qrs], color="red", label="Pred: QRS", marker="x", s=30)
        axs[1].scatter(pred_t, X_test[i][pred_t], color="green", label="Pred: T", marker="x", s=30)
        axs[1].legend()
        axs[1].grid()

        save_path = f"predictions/ecg_prediction_{records[i]}.png"
        plt.savefig(save_path)
        plt.close()

    print(f"[INFO] Zapisano wykresy do folderu 'predictions/'")


def run_model_inference_on_ludb():
    X_test, Y_test, records = load_test_data(num_samples=50)
    preds = model.predict(X_test)
    pred_labels = np.argmax(preds, axis=-1)

    plot_predictions(X_test, Y_test, pred_labels, records)




# Podział sygnału na fragmenty
def segment_signal(signal, window_size):
    num_segments = len(signal) // window_size
    segments = [signal[i * window_size:(i + 1) * window_size] for i in range(num_segments)]
    return np.array(segments)



# Wczytanie sygnału i predykcja
def process_and_predict(record_name):
    record_path = os.path.join(QTDB_PATH, record_name)
    try:
        record = wfdb.rdrecord(record_path)
        best_lead = None
        for lead in PREFERRED_LEADS:
            if lead in record.sig_name:
                best_lead = record.p_signal[:, record.sig_name.index(lead)]
                break
        if best_lead is None:
            print(f"[WARNING] Brak odpowiedniego leadu w {record_name}")
            return None, None

        resampled_signal = resample_signal(best_lead, record.fs, TARGET_FS)
        return resampled_signal, record_name
    except Exception as e:
        print(f"[ERROR] Nie udało się wczytać {record_name}: {e}")
        return None, None

# Wizualizacja wyników
def plot_prediction(signal, pred_labels, record_name):
    plt.figure(figsize=(12, 6))
    plt.plot(signal, label="Sygnał EKG", color="black")
    p_idx = np.where(pred_labels == 1)[0]
    qrs_idx = np.where(pred_labels == 2)[0]
    t_idx = np.where(pred_labels == 3)[0]
    plt.scatter(p_idx, signal[p_idx], color="blue", label="P", marker="o")
    plt.scatter(qrs_idx, signal[qrs_idx], color="red", label="QRS", marker="x")
    plt.scatter(t_idx, signal[t_idx], color="green", label="T", marker="s")
    plt.title(f"Predykcja dla {record_name}")
    plt.legend()
    plt.grid()
    plt.show()


"""
def run_single_prediction():
    record_name = choice(list(OK_RECORDS_QTDB))
    signal, record_name = process_and_predict(record_name)
    if signal is None or len(signal) < WINDOW_SIZE:
        print(f"[ERROR] Sygnał {record_name} jest zbyt krótki")
        return

    start_idx = np.random.randint(0, len(signal) - WINDOW_SIZE)
    signal_fragment = signal[start_idx:start_idx + WINDOW_SIZE]
    signal_fragment = (signal_fragment - np.mean(signal_fragment)) / (np.std(signal_fragment) + 1e-8)
    signal_input = signal_fragment.reshape(1, WINDOW_SIZE, 1)

    preds = model.predict(signal_input)
    pred_labels = np.argmax(preds, axis=-1).flatten()

    plot_prediction(signal_fragment, pred_labels, record_name)
"""

if __name__ == "__main__":
    main()
    #run_model_inference_on_ludb()





