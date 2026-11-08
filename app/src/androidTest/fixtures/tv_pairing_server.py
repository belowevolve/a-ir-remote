import socket,ssl,threading,json,time,argparse
from pathlib import Path
parser=argparse.ArgumentParser(description='Local TLS TV pairing fixture: cancel, Back, delayed cancel, retry and successful PIN')
parser.add_argument('--cert',required=True)
parser.add_argument('--key',required=True)
parser.add_argument('--stats',required=True)
parser.add_argument('--host',default='127.0.0.1')
parser.add_argument('--port',type=int,default=16467)
args=parser.parse_args()
OUT=Path(args.stats)
state={'connections':0,'ready':0,'closed':0}
lock=threading.Lock()
def save():OUT.write_text(json.dumps(state))
def vint(n):
 b=bytearray()
 while n>127:b.append((n&127)|128);n>>=7
 b.append(n)
 return bytes(b)
def read_frame(s):
 n=0;shift=0
 while True:
  b=s.recv(1)
  if not b:raise EOFError()
  n|=(b[0]&127)<<shift
  if not b[0]&128:break
  shift+=7
  if shift>28:raise ValueError()
 if n>4096:raise ValueError()
 out=b''
 while len(out)<n:
  b=s.recv(n-len(out))
  if not b:raise EOFError()
  out+=b
 return out
ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(args.cert,args.key)
def session(raw):
 with lock:state['connections']+=1;attempt=state['connections'];save()
 try:
  with ctx.wrap_socket(raw,server_side=True) as s:
   s.settimeout(15)
   for field in [11,20,31]:
    read_frame(s)
    if attempt==3 and field==11:time.sleep(.7)
    reply=bytes.fromhex('080210c801')+vint((field<<3)|2)+b'\x00'
    s.sendall(vint(len(reply))+reply)
   with lock:state['ready']+=1;save()
   secret=read_frame(s)[-34:]
   if len(secret)!=34 or secret[:2]!=b'\x0a\x20':raise ValueError()
   reply=bytes.fromhex('080210c801')+vint((41<<3)|2)+vint(len(secret))+secret
   s.sendall(vint(len(reply))+reply)
   while s.recv(1024):pass
 except (OSError,EOFError,ValueError):pass
 finally:
  with lock:state['closed']+=1;save()
with socket.socket() as listener:
 listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
 listener.bind((args.host,args.port));listener.listen(4)
 print('Fake TV TLS fixture ready',flush=True)
 try:
  while True:
   raw,_=listener.accept();threading.Thread(target=session,args=(raw,),daemon=True).start()
 except KeyboardInterrupt:pass
