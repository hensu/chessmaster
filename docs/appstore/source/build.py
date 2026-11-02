import os, subprocess
R=os.path.abspath("raw")
G=lambda s:f'<span class="g">{s}</span>'
shots={
"setA/01":("Your Personal<br>"+G("AI Chess Coach"),"Notes on every mistake, in your own games","an3"),
"setA/02":("<span style=\"font-size:100px\">Every Game Reviewed,</span><br>"+G("Plain English"),"A coach&rsquo;s report after every game","report"),
"setA/03":("See Your Mistakes,<br>"+G("Best Moves"),"Red is what you played. Green is better.","km2"),
"setA/04":("An Opponent<br>That "+G("Matches You"),"Auto level grows with you, Newcomer to Grandmaster","auto"),
"setA/05":(G("1,369 Puzzles")+"<br>to Sharpen Tactics","Forks, pins, mates and more, plus a daily puzzle","daily"),
"setB/01":("Learn Chess<br>"+G("the Smart Way"),"An AI coach that explains every mistake","an3"),
"setB/02":(G("16 Lessons")+"<br>From Zero","From how the pieces move to checkmate patterns","learn3"),
"setB/03":("Puzzles Matched<br>to "+G("Your Level"),"A difficulty ladder from 600 to 2,800","daily"),
"setB/04":("Play a Bot<br>"+G("That Adapts"),"Ten levels, Newcomer to Grandmaster","auto"),
"setB/05":("Free Game Review<br>"+G("After Every Match"),"Accuracy, key moments and best moves","an2"),
}
css="""*{margin:0;padding:0;box-sizing:border-box}
html,body{width:1290px;height:2796px;overflow:hidden;background:#14100D}
body{position:relative;font-family:'Iowan Old Style','Palatino',serif;
background:radial-gradient(ellipse 900px 1100px at 50% 1650px,rgba(127,184,74,.16),rgba(127,184,74,0) 70%),
radial-gradient(ellipse 1100px 700px at 50% 200px,rgba(240,217,181,.08),rgba(240,217,181,0) 70%),#14100D}
.head{position:absolute;top:185px;left:60px;right:60px;text-align:center}
h1{font-weight:700;font-size:112px;line-height:1.08;color:#F0D9B5;letter-spacing:-1px}
.g{color:#7FB84A}
p{margin-top:34px;font-family:-apple-system,'Helvetica Neue',sans-serif;font-size:44px;color:rgba(240,217,181,.68);font-weight:500}
.phone{position:absolute;left:145px;top:640px;width:1000px;height:2122px;border-radius:128px;background:#0b0908;
padding:22px;box-shadow:0 0 0 3px #3a3029,0 60px 140px rgba(0,0,0,.75),0 0 90px rgba(127,184,74,.12)}
.phone img{width:956px;height:2078px;border-radius:106px;display:block}
"""
for out,(h,sub,img) in shots.items():
    f=f"html/{out.replace('/','_')}.html"
    open(f,"w").write(f'<!doctype html><html><head><meta charset="utf-8"><style>{css}</style></head><body><div class="head"><h1 style="{'font-size:100px' if out=='setA/02' else ''}">{h}</h1><p>{sub}</p></div><div class="phone"><img src="file://{R}/{img}.png"></div></body></html>')
    subprocess.run(["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome","--headless","--disable-gpu","--force-device-scale-factor=1",f"--screenshot={os.path.abspath(out)}.png","--window-size=1290,2796","--hide-scrollbars","--allow-file-access-from-files","file://"+os.path.abspath(f)],capture_output=True)
