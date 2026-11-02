# Header (3840x1646, 21:9) and Search Results (3840x2560, 3:2) creative assets.
import os, subprocess
R=os.path.abspath("raw")
OUT=os.path.abspath("../creative")
base="""*{margin:0;padding:0;box-sizing:border-box}
html,body{width:%dpx;height:%dpx;overflow:hidden;background:#14100D}
body{position:relative;font-family:'Iowan Old Style','Palatino',serif;
background:radial-gradient(ellipse 60%% 70%% at 50%% 55%%,rgba(127,184,74,.18),rgba(127,184,74,0) 70%%),
radial-gradient(ellipse 70%% 50%% at 50%% 0%%,rgba(240,217,181,.08),rgba(240,217,181,0) 70%%),#14100D}
h1{font-weight:700;color:#F0D9B5;letter-spacing:-2px;line-height:1.06}
.g{color:#7FB84A}
p{font-family:-apple-system,'Helvetica Neue',sans-serif;color:rgba(240,217,181,.7);font-weight:500}
.phone{position:absolute;border-radius:9%%/4.2%%;background:#0b0908;padding:1.1%%;
box-shadow:0 0 0 4px #3a3029,0 80px 180px rgba(0,0,0,.8),0 0 120px rgba(127,184,74,.14)}
.phone img{width:100%%;display:block;border-radius:8%%/3.8%%}
"""
def phone(img,x,y,w,rot=0):
    return f'<div class="phone" style="left:{x}px;top:{y}px;width:{w}px;transform:rotate({rot}deg)"><img src="file://{R}/{img}.png"></div>'
header=f"""<style>{base%(3840,1646)}</style>
{phone("an3",330,250,760,-6)}{phone("report",2750,250,760,6)}
<div style="position:absolute;left:1080px;right:1080px;top:470px;text-align:center">
<h1 style="font-size:170px;white-space:nowrap">Your Personal 24×7<br><span class="g">AI Chess Coach</span></h1>
<p style="font-size:66px;margin-top:60px">Every game reviewed, in plain English</p></div>"""
search=f"""<style>{base%(3840,2560)}</style>
<div style="position:absolute;left:0;right:0;top:150px;text-align:center">
<h1 style="font-size:170px">Your Personal 24×7 <span class="g">AI Chess Coach</span></h1>
<p style="font-size:64px;margin-top:40px">Play, get every mistake explained, and improve</p></div>
{phone("an3",470,640,880)}{phone("report",1480,640,880)}{phone("daily",2490,640,880)}"""
for name,(w,h,body) in {"header":(3840,1646,header),"search":(3840,2560,search)}.items():
    f=os.path.abspath(f"html/creative_{name}.html")
    open(f,"w").write(f'<!doctype html><html><head><meta charset="utf-8"></head><body>{body}</body></html>')
    subprocess.run(["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome","--headless","--disable-gpu","--force-device-scale-factor=1",f"--screenshot={OUT}/{name}.png",f"--window-size={w},{h}","--hide-scrollbars","--allow-file-access-from-files","file://"+f],capture_output=True)
