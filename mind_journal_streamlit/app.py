import streamlit as st
import sqlite3
import pandas as pd
from datetime import datetime

conn = sqlite3.connect('journal.db', check_same_thread=False)
c = conn.cursor()

c.execute('''CREATE TABLE IF NOT EXISTS journal (
id INTEGER PRIMARY KEY AUTOINCREMENT,
created_at TEXT,
entry_type TEXT,
content TEXT,
emotion TEXT,
intensity INTEGER
)''')

c.execute('''CREATE TABLE IF NOT EXISTS meditation (
id INTEGER PRIMARY KEY AUTOINCREMENT,
created_at TEXT,
duration INTEGER,
mood_before INTEGER,
mood_after INTEGER,
dominant_emotion TEXT,
recurring_thought TEXT,
insight TEXT
)''')
conn.commit()

st.set_page_config(page_title='Mind Journal', page_icon='🧠')
st.title('🧠 Mind Journal')

tab1, tab2, tab3, tab4 = st.tabs(['Daily Journal','Trigger Log','Meditation','Insights'])

with tab1:
    goal = st.text_area('What matters most today?')
    if st.button('Save Journal'):
        c.execute('INSERT INTO journal(created_at,entry_type,content,emotion,intensity) VALUES (?,?,?,?,?)',
                  (datetime.now().isoformat(),'daily',goal,'',0))
        conn.commit()
        st.success('Saved')

with tab2:
    event = st.text_area('What happened?')
    emotion = st.selectbox('Emotion',['Anger','Jealousy','Anxiety','Sadness','Disappointment'])
    intensity = st.slider('Intensity',1,10,5)
    if st.button('Save Trigger'):
        c.execute('INSERT INTO journal(created_at,entry_type,content,emotion,intensity) VALUES (?,?,?,?,?)',
                  (datetime.now().isoformat(),'trigger',event,emotion,intensity))
        conn.commit()
        st.success('Saved')

with tab3:
    duration = st.slider('Meditation Minutes',1,60,10)
    before = st.slider('Mood Before',1,10,5)
    after = st.slider('Mood After',1,10,5)
    insight = st.text_area('Insight')
    if st.button('Save Meditation'):
        c.execute('INSERT INTO meditation(created_at,duration,mood_before,mood_after,dominant_emotion,recurring_thought,insight) VALUES (?,?,?,?,?,?,?)',
                  (datetime.now().isoformat(),duration,before,after,'','','%s' % insight))
        conn.commit()
        st.success('Saved')

with tab4:
    df = pd.read_sql_query("SELECT * FROM journal", conn)
    if not df.empty:
        st.metric('Entries', len(df))
        trigger_df = df[df['entry_type']=='trigger'].copy()
        if not trigger_df.empty:
            trigger_df['created_at'] = pd.to_datetime(trigger_df['created_at'])
            trigger_df['week'] = trigger_df['created_at'].dt.strftime('%Y-W%U')
            st.subheader('Weekly Emotion Trends')
            pivot = trigger_df.groupby(['week','emotion']).size().reset_index(name='count').pivot(index='week', columns='emotion', values='count').fillna(0)
            st.line_chart(pivot)
            st.subheader('Weekly Intensity')
            weekly = trigger_df.groupby('week')['intensity'].mean()
            st.line_chart(weekly)
