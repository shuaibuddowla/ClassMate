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
        
        # Soft-clip saturation for heavy punch without digital harsh clipping
        for l, r in samples:
            l = math.tanh(l)
            r = math.tanh(r)
            l_int = max(-32767, min(32767, int(l * 32000)))
            r_int = max(-32767, min(32767, int(r * 32000)))
            packed.extend(struct.pack('<hh', l_int, r_int))
        wav.writeframes(packed)

def convert_to_formats(wav_path, base_name):
    mp3_path = f"notification_previews/{base_name}.mp3"
    ogg_path = f"notification_previews/{base_name}.ogg"
    raw_ogg_path = f"app/src/main/res/raw/{base_name}.ogg"
    
    subprocess.run(['ffmpeg', '-y', '-i', wav_path, '-b:a', '256k', mp3_path], capture_output=True)
    subprocess.run(['ffmpeg', '-y', '-i', wav_path, '-c:a', 'libvorbis', '-q:a', '7', ogg_path], capture_output=True)
    subprocess.run(['ffmpeg', '-y', '-i', wav_path, '-c:a', 'libvorbis', '-q:a', '7', raw_ogg_path], capture_output=True)
    print(f"Generated {base_name}.mp3 and {base_name}.ogg")

# =========================================================================
# 1. THE CINEMATIC PULSE (Netflix / PlayStation-tier deep punch + majestic chord)
# =========================================================================
def generate_cinematic_pulse():
    duration = 1.1
    total_samples = int(SAMPLE_RATE * duration)
    samples = [[0.0, 0.0] for _ in range(total_samples)]
    
    # Chord: D Major 9th (D3=146.83, A3=220.0, F#4=369.99, E5=659.25, A5=880.0)
    chord_freqs = [
        (146.83, 0.50, 4.0, 0.0),    # Warm rich D3 base
        (220.00, 0.40, 4.5, -0.3),   # A3 wide left
        (369.99, 0.45, 5.0, 0.3),    # F#4 wide right
        (659.25, 0.55, 6.0, -0.15),  # E5 lush 9th
        (880.00, 0.40, 7.5, 0.15),   # A5 top shimmer
        (1318.51, 0.25, 12.0, 0.0)   # High sparkle
    ]
    
    for i in range(total_samples):
        t = i / SAMPLE_RATE
        
        # 1. Heavy Sub-Bass Transient (Thump: 85 Hz sweeping to 48 Hz with punchy attack)
        thump = 0.0
        if t < 0.35:
            thump_f = 85.0 - (t / 0.35) * 37.0
            thump_env = min(1.0, t / 0.003) * math.exp(-t * 12.0)
            thump = (math.sin(2 * math.pi * thump_f * t) + 0.3 * math.sin(4 * math.pi * thump_f * t)) * thump_env * 1.3
        
        # 2. Majestic Stereo Chord Swell
        chord_l = 0.0
        chord_r = 0.0
        for freq, amp, decay_rate, pan in chord_freqs:
            attack = min(1.0, t / 0.015)  # Smooth authoritative swell
            env = attack * math.exp(-t * decay_rate)
            # Add slight analog detune/chorus for massive stereo width
            sig_l = math.sin(2 * math.pi * (freq * 0.998) * t) * amp * env
            sig_r = math.sin(2 * math.pi * (freq * 1.002) * t) * amp * env
            gain_l = 0.5 * (1.0 - pan)
            gain_r = 0.5 * (1.0 + pan)
            chord_l += sig_l * gain_l
            chord_r += sig_r * gain_r
            
        samples[i][0] = thump * 0.6 + chord_l * 0.75
        samples[i][1] = thump * 0.6 + chord_r * 0.75

    wav_path = "notification_previews/classmate_cinematic_pulse.wav"
    create_wav(wav_path, samples)
    convert_to_formats(wav_path, "classmate_cinematic_pulse")

# =========================================================================
# 2. THE CYBERPUNK POWER (Futuristic energy ignition + laser-crisp strike)
# =========================================================================
def generate_cyber_power():
    duration = 0.85
    total_samples = int(SAMPLE_RATE * duration)
    samples = [[0.0, 0.0] for _ in range(total_samples)]
    
    for i in range(total_samples):
        t = i / SAMPLE_RATE
        val_l = 0.0
        val_r = 0.0
        
        # Sub Power Drop at t=0
        if t < 0.25:
            sub_f = 140.0 * math.exp(-t * 14.0) + 40.0
            sub_env = min(1.0, t / 0.002) * math.exp(-t * 15.0)
            sub_sig = math.sin(2 * math.pi * sub_f * t) * sub_env * 1.1
            val_l += sub_sig
            val_r += sub_sig
            
        # Fast ascending laser power notes (E5=659, B5=987, G#6=1661)
        notes = [
            (0.00, 659.25, -0.2),
            (0.05, 987.77, 0.2),
            (0.10, 1661.22, 0.0)
        ]
        for n_start, n_f, n_pan in notes:
            if t >= n_start:
                nt = t - n_start
                n_attack = min(1.0, nt / 0.002)
                n_env = n_attack * math.exp(-nt * 9.0)
                # Rich saw/square hybrid with harmonics
                sig = (math.sin(2 * math.pi * n_f * nt) + 
                       0.4 * math.sin(4 * math.pi * n_f * nt) +
                       0.2 * math.sin(6 * math.pi * n_f * nt)) * n_env * 0.55
                gain_l = 0.5 * (1.0 - n_pan)
                gain_r = 0.5 * (1.0 + n_pan)
                val_l += sig * gain_l
                val_r += sig * gain_r
                
        # Top crystal electric shimmer at t=0.10
        if t >= 0.10:
            st = t - 0.10
            s_env = min(1.0, st / 0.001) * math.exp(-st * 16.0)
            shimmer = math.sin(2 * math.pi * 3322.0 * st) * 0.2 * s_env
            val_l += shimmer
            val_r += shimmer
            
        samples[i][0] = val_l
        samples[i][1] = val_r

    wav_path = "notification_previews/classmate_cyber_power.wav"
    create_wav(wav_path, samples)
    convert_to_formats(wav_path, "classmate_cyber_power")

# =========================================================================
# 3. THE TITAN (Heavy bronze authority + resonant orchestral boom)
# =========================================================================
def generate_titan():
    duration = 1.3
    total_samples = int(SAMPLE_RATE * duration)
    samples = [[0.0, 0.0] for _ in range(total_samples)]
    
    # Heavy low bronze fundamental + high overtone matrix
    bell_modes = [
        (98.00, 0.70, 3.0, 0.0),    # Massive G2 thunder fundamental
        (196.00, 0.50, 3.8, 0.0),   # G3 octave
        (392.00, 0.60, 4.5, -0.2),  # G4 body strike
        (587.33, 0.45, 5.2, 0.2),   # D5 power 5th
        (783.99, 0.50, 6.0, -0.1),  # G5 ring
        (1174.66, 0.40, 8.0, 0.1),  # D6 high shimmer
        (1567.98, 0.25, 11.0, 0.0), # G6 top cut
        (2349.32, 0.15, 18.0, 0.0)  # Mallet ping
    ]
    
    for i in range(total_samples):
        t = i / SAMPLE_RATE
        val_l = 0.0
        val_r = 0.0
        
        # Heavy Mallet Impact Transient
        impact = 0.0
        if t < 0.08:
            imp_env = min(1.0, t / 0.001) * math.exp(-t * 60.0)
            impact = (math.sin(2 * math.pi * 60.0 * t) + 0.5 * math.sin(2 * math.pi * 180.0 * t)) * imp_env * 0.9
            
        for freq, amp, decay_rate, pan in bell_modes:
            attack = min(1.0, t / 0.004)
            env = attack * math.exp(-t * decay_rate)
            # Subtle detuning shimmer
            sig_l = math.sin(2 * math.pi * (freq * 0.999) * t) * amp * env
            sig_r = math.sin(2 * math.pi * (freq * 1.001) * t) * amp * env
            gain_l = 0.5 * (1.0 - pan)
            gain_r = 0.5 * (1.0 + pan)
            val_l += sig_l * gain_l
            val_r += sig_r * gain_r
            
        samples[i][0] = impact * 0.5 + val_l * 0.65
        samples[i][1] = impact * 0.5 + val_r * 0.65

    wav_path = "notification_previews/classmate_titan.wav"
    create_wav(wav_path, samples)
    convert_to_formats(wav_path, "classmate_titan")

# =========================================================================
# 4. THE HORIZON (Rhythmic double-hit + triumphant orchestral power chord)
# =========================================================================
def generate_horizon():
    duration = 1.05
    total_samples = int(SAMPLE_RATE * duration)
    samples = [[0.0, 0.0] for _ in range(total_samples)]
    
    # Hit 1: Sub Bass Boom at t=0
    # Hit 2: Massive F Major Triumph Chord at t=0.10s (F3=174.6, C4=261.6, A4=440.0, C5=523.25, F5=698.46)
    chord_partials = [
        (174.61, 0.55, 3.5, 0.0),    # F3 Foundation
        (261.63, 0.45, 4.2, -0.25),  # C4 Fifth
        (440.00, 0.50, 4.8, 0.25),   # A4 Major third
        (523.25, 0.55, 5.8, -0.15),  # C5 Octave
        (698.46, 0.45, 6.8, 0.15),   # F5 Top ring
        (1046.50, 0.30, 9.5, 0.0),   # High C6 sparkle
        (1396.91, 0.20, 14.0, 0.0)   # Air
    ]
    
    for i in range(total_samples):
        t = i / SAMPLE_RATE
        val_l = 0.0
        val_r = 0.0
        
        # Hit 1: Heavy Kick Transient at t=0.0
        if t < 0.18:
            k_f = 110.0 - (t / 0.18) * 60.0
            k_env = min(1.0, t / 0.002) * math.exp(-t * 22.0)
            kick = math.sin(2 * math.pi * k_f * t) * k_env * 1.1
            val_l += kick
            val_r += kick
            
        # Hit 2: Glorious Chord Bloom at t=0.09s
        if t >= 0.09:
            ct = t - 0.09
            for freq, amp, decay_rate, pan in chord_partials:
                attack = min(1.0, ct / 0.008)
                env = attack * math.exp(-ct * decay_rate)
                # Stereo chorus richness
                s_l = math.sin(2 * math.pi * (freq * 0.9985) * ct) * amp * env
                s_r = math.sin(2 * math.pi * (freq * 1.0015) * ct) * amp * env
                gain_l = 0.5 * (1.0 - pan)
                gain_r = 0.5 * (1.0 + pan)
                val_l += s_l * gain_l
                val_r += s_r * gain_r
                
        samples[i][0] = val_l * 0.75
        samples[i][1] = val_r * 0.75

    wav_path = "notification_previews/classmate_horizon.wav"
    create_wav(wav_path, samples)
    convert_to_formats(wav_path, "classmate_horizon")

if __name__ == "__main__":
    generate_cinematic_pulse()
    generate_cyber_power()
    generate_titan()
    generate_horizon()
    print("All 4 POWERFUL notification sounds generated successfully!")
