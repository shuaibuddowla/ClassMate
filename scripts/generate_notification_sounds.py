import math
import struct
import wave
import subprocess
import os

SAMPLE_RATE = 44100

def create_wav(filename, samples):
    with wave.open(filename, 'wb') as wav:
        wav.setnchannels(2)  # Stereo
        wav.setsampwidth(2)  # 16-bit
        wav.setframerate(SAMPLE_RATE)
        packed = bytearray()
        for l, r in samples:
            l = max(-32767, min(32767, int(l * 32767)))
            r = max(-32767, min(32767, int(r * 32767)))
            packed.extend(struct.pack('<hh', l, r))
        wav.writeframes(packed)

def convert_to_formats(wav_path, base_name):
    mp3_path = f"notification_previews/{base_name}.mp3"
    ogg_path = f"notification_previews/{base_name}.ogg"
    raw_ogg_path = f"app/src/main/res/raw/{base_name}.ogg"
    
    subprocess.run(['ffmpeg', '-y', '-i', wav_path, '-b:a', '192k', mp3_path], capture_output=True)
    subprocess.run(['ffmpeg', '-y', '-i', wav_path, '-c:a', 'libvorbis', '-q:a', '6', ogg_path], capture_output=True)
    subprocess.run(['ffmpeg', '-y', '-i', wav_path, '-c:a', 'libvorbis', '-q:a', '6', raw_ogg_path], capture_output=True)
    print(f"Generated {base_name}.mp3 and {base_name}.ogg")

# --- Sound 1: The Campus Marimba ---
def generate_marimba():
    duration = 0.7
    total_samples = int(SAMPLE_RATE * duration)
    samples = [[0.0, 0.0] for _ in range(total_samples)]
    
    notes = [
        (0.00, 783.99, -0.15, 0.35),  # G5, pan left, duration 0.35s
        (0.11, 1046.50, 0.15, 0.45)   # C6, pan right, duration 0.45s
    ]
    
    for start_t, freq, pan, dur in notes:
        start_idx = int(start_t * SAMPLE_RATE)
        num_s = int(dur * SAMPLE_RATE)
        for i in range(num_s):
            idx = start_idx + i
            if idx >= total_samples: break
            t = i / SAMPLE_RATE
            
            # Attack and exponential decay
            attack = min(1.0, t / 0.004)
            decay = math.exp(-t * 9.5)
            env = attack * decay
            
            # Fundamental + resonant bar overtone (3.9x) + subtle warm sub
            s_fund = math.sin(2 * math.pi * freq * t)
            s_over = math.sin(2 * math.pi * (freq * 3.9) * t) * math.exp(-t * 22.0) * 0.3
            s_warm = math.sin(2 * math.pi * (freq * 0.5) * t) * 0.1
            
            val = (s_fund + s_over + s_warm) * env * 0.75
            
            # Apply pan
            gain_l = 0.5 * (1.0 - pan)
            gain_r = 0.5 * (1.0 + pan)
            samples[idx][0] += val * gain_l
            samples[idx][1] += val * gain_r
            
    wav_path = "notification_previews/classmate_marimba.wav"
    create_wav(wav_path, samples)
    convert_to_formats(wav_path, "classmate_marimba")

# --- Sound 2: The Academic Bell ---
def generate_bell():
    duration = 1.2
    total_samples = int(SAMPLE_RATE * duration)
    samples = [[0.0, 0.0] for _ in range(total_samples)]
    
    # Inharmonic bell partials (classic chime bell spectrum)
    partials = [
        (523.25, 0.35, 3.2, 0.0),    # Hum tone (C5)
        (1046.50, 0.65, 5.0, 0.0),   # Strike tone (C6)
        (1048.00, 0.30, 4.8, 0.0),   # Beating shimmer (+1.5Hz)
        (1244.50, 0.40, 6.5, -0.1),  # Tierce (minor 3rd)
        (1567.98, 0.45, 6.0, 0.1),   # Quint (5th)
        (2093.00, 0.30, 8.0, 0.0),   # Nominal (octave)
        (3135.96, 0.20, 15.0, 0.05), # High ring
        (4186.00, 0.15, 25.0, -0.05) # Strike transient
    ]
    
    for i in range(total_samples):
        t = i / SAMPLE_RATE
        val_l = 0.0
        val_r = 0.0
        for freq, amp, decay_rate, pan in partials:
            attack = min(1.0, t / 0.002)
            env = attack * math.exp(-t * decay_rate)
            sig = math.sin(2 * math.pi * freq * t) * amp * env
            gain_l = 0.5 * (1.0 - pan)
            gain_r = 0.5 * (1.0 + pan)
            val_l += sig * gain_l
            val_r += sig * gain_r
            
        samples[i][0] = val_l * 0.70
        samples[i][1] = val_r * 0.70
        
    wav_path = "notification_previews/classmate_bell.wav"
    create_wav(wav_path, samples)
    convert_to_formats(wav_path, "classmate_bell")

# --- Sound 3: The Snappy Pop-Chime ---
def generate_pop_chime():
    duration = 0.6
    total_samples = int(SAMPLE_RATE * duration)
    samples = [[0.0, 0.0] for _ in range(total_samples)]
    
    # Pop 1 at t=0
    # Pop 2 at t=0.06
    # Crystal chime at t=0.10
    for i in range(total_samples):
        t = i / SAMPLE_RATE
        val = 0.0
        
        # Pop 1
        if 0.0 <= t < 0.06:
            p_t = t
            p_freq = 420.0 + (p_t / 0.04) * 500.0  # Pitch glide up
            p_env = min(1.0, p_t / 0.003) * math.exp(-p_t * 50.0)
            val += math.sin(2 * math.pi * p_freq * p_t) * p_env * 0.8
            
        # Pop 2
        if 0.055 <= t < 0.12:
            p_t = t - 0.055
            p_freq = 600.0 + (p_t / 0.04) * 600.0
            p_env = min(1.0, p_t / 0.003) * math.exp(-p_t * 45.0)
            val += math.sin(2 * math.pi * p_freq * p_t) * p_env * 0.7
            
        # Crystal Chime at t=0.10
        if t >= 0.10:
            c_t = t - 0.10
            c_env = min(1.0, c_t / 0.002) * math.exp(-c_t * 8.5)
            chime = (math.sin(2 * math.pi * 1760.0 * c_t) * 0.5 + 
                     math.sin(2 * math.pi * 2637.0 * c_t) * 0.35 +
                     math.sin(2 * math.pi * 3520.0 * c_t) * 0.15)
            val += chime * c_env * 0.75
            
        samples[i][0] = val
        samples[i][1] = val
        
    wav_path = "notification_previews/classmate_pop_chime.wav"
    create_wav(wav_path, samples)
    convert_to_formats(wav_path, "classmate_pop_chime")

# --- Sound 4: The High-Alert Arpeggio ---
def generate_arpeggio():
    duration = 0.75
    total_samples = int(SAMPLE_RATE * duration)
    samples = [[0.0, 0.0] for _ in range(total_samples)]
    
    # 3 ascending pluck notes: C6 (1046.5), E6 (1318.5), G6 (1567.98)
    notes = [
        (0.00, 1046.50, -0.2, 0.35),
        (0.07, 1318.50,  0.0, 0.35),
        (0.14, 1567.98,  0.2, 0.55)
    ]
    
    for start_t, freq, pan, dur in notes:
        start_idx = int(start_t * SAMPLE_RATE)
        num_s = int(dur * SAMPLE_RATE)
        for i in range(num_s):
            idx = start_idx + i
            if idx >= total_samples: break
            t = i / SAMPLE_RATE
            
            attack = min(1.0, t / 0.002)
            decay = math.exp(-t * 8.0)
            env = attack * decay
            
            # Harmonic string pluck synthesis
            h1 = math.sin(2 * math.pi * freq * t)
            h2 = math.sin(2 * math.pi * (freq * 2) * t) * math.exp(-t * 12.0) * 0.5
            h3 = math.sin(2 * math.pi * (freq * 3) * t) * math.exp(-t * 18.0) * 0.25
            h4 = math.sin(2 * math.pi * (freq * 4) * t) * math.exp(-t * 25.0) * 0.15
            
            val = (h1 + h2 + h3 + h4) * env * 0.65
            
            gain_l = 0.5 * (1.0 - pan)
            gain_r = 0.5 * (1.0 + pan)
            samples[idx][0] += val * gain_l
            samples[idx][1] += val * gain_r
            
    wav_path = "notification_previews/classmate_arpeggio.wav"
    create_wav(wav_path, samples)
    convert_to_formats(wav_path, "classmate_arpeggio")

if __name__ == "__main__":
    generate_marimba()
    generate_bell()
    generate_pop_chime()
    generate_arpeggio()
    print("All 4 notification sounds generated successfully!")
