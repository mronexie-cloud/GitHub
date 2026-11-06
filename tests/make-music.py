# Makes test songs for the music player's emulator test: tags, covers and lyrics in each format the app reads.
import os, subprocess
import imageio_ffmpeg
from mutagen.id3 import ID3, TIT2, TPE1, TALB, USLT, APIC
from mutagen.flac import FLAC, Picture
from mutagen.mp4 import MP4, MP4Cover

FF = imageio_ffmpeg.get_ffmpeg_exe()
M, H = 'music-fixtures/Music', 'music-fixtures/hidden'
os.makedirs(M, exist_ok=True)
os.makedirs(H, exist_ok=True)

def ff(*a):
    subprocess.run([FF, '-y', '-loglevel', 'error', *a], check=True)

def tone(out, sec, freq, *extra):
    ff('-f', 'lavfi', '-i', f'sine=frequency={freq}:duration={sec}', '-ac', '2', *extra, out)

ff('-f', 'lavfi', '-i', 'testsrc=size=600x600', '-frames:v', '1', 'cover.jpg')
ff('-f', 'lavfi', '-i', 'mandelbrot=size=600x600', '-frames:v', '1', 'cover2.jpg')
cover = open('cover.jpg', 'rb').read()
cover2 = open('cover2.jpg', 'rb').read()

lrc1 = '\n'.join(['[ti:测试歌曲一]', '[ar:测试歌手]'] + [f'[00:{s:02d}.50]第{i + 1}句：这是同步歌词测试\n[00:{s:02d}.50]Line {i + 1}: synced lyrics test' for i, s in enumerate(range(0, 44, 4))])
lrc2 = '\n'.join(f'[00:{s:02d}.00]FLAC lyric line {i + 1}' for i, s in enumerate(range(1, 40, 3)))

# MP3 with ID3 tags, cover and synced lyrics (USLT)
tone(f'{M}/测试歌曲一.mp3', 45, 440, '-b:a', '128k')
t = ID3()
t.add(TIT2(encoding=3, text='测试歌曲一'))
t.add(TPE1(encoding=3, text='测试歌手'))
t.add(TALB(encoding=3, text='测试专辑'))
t.add(USLT(encoding=3, lang='chi', desc='', text=lrc1))
t.add(APIC(encoding=3, mime='image/jpeg', type=3, desc='cover', data=cover))
t.save(f'{M}/测试歌曲一.mp3', v2_version=3)

# FLAC with a LYRICS comment and a picture
tone(f'{M}/second.flac', 40, 523)
f = FLAC(f'{M}/second.flac')
f['title'] = 'Second Song'
f['artist'] = 'Tester'
f['album'] = 'Demo Album'
f['LYRICS'] = lrc2
pic = Picture()
pic.type = 3
pic.mime = 'image/jpeg'
pic.data = cover2
f.add_picture(pic)
f.save()

# M4A with plain lyrics in the ©lyr atom
tone(f'{M}/third.m4a', 38, 659, '-c:a', 'aac', '-b:a', '96k')
m = MP4(f'{M}/third.m4a')
m['\xa9nam'] = '第三首（M4A）'
m['\xa9ART'] = '测试歌手'
m['\xa9lyr'] = 'M4A 内嵌歌词第一行\nM4A 内嵌歌词第二行\n没有时间轴的普通歌词'
m['covr'] = [MP4Cover(cover, imageformat=MP4Cover.FORMAT_JPEG)]
m.save()

# a real song title (silent audio of the same length) to test the online lyrics lookup
ff('-f', 'lavfi', '-i', 'anullsrc=r=22050:cl=mono', '-t', '354', '-c:a', 'aac', '-b:a', '16k', f'{M}/online-test.m4a')
m = MP4(f'{M}/online-test.m4a')
m['\xa9nam'] = 'Bohemian Rhapsody'
m['\xa9ART'] = 'Queen'
m.save()

# too short: should be skipped by the default 30 s filter
tone(f'{M}/short-beep.mp3', 5, 880)

# a folder hidden from media apps (.nomedia): found by the folder walk
open(f'{H}/.nomedia', 'w').close()
tone(f'{H}/隐藏的歌.mp3', 40, 392, '-b:a', '128k')
t = ID3()
t.add(TIT2(encoding=3, text='隐藏文件夹里的歌'))
t.add(TPE1(encoding=3, text='隐藏歌手'))
t.save(f'{H}/隐藏的歌.mp3', v2_version=4)
print(sorted(os.listdir(M)), sorted(os.listdir(H)))
