import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const GoobleApp());

class GoobleApp extends StatelessWidget {
  const GoobleApp({super.key});
  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      home: GoobleHome(),
      debugShowCheckedModeBanner: false,
    );
  }
}

class GoobleHome extends StatefulWidget {
  const GoobleHome({super.key});
  @override
  State<GoobleHome> createState() => _GoobleHomeState();
}

class _GoobleHomeState extends State<GoobleHome> {
  static const channel = MethodChannel('com.example.gooble/overlay');
  bool running = false;
  String status = 'Gooble is sleeping...';

  Future<void> toggleGooble() async {
    try {
      if (running) {
        await channel.invokeMethod('stopGooble');
        setState(() { running = false; status = 'Gooble is sleeping...'; });
      } else {
        final result = await channel.invokeMethod('startGooble');
        setState(() {
          if (result == 'started') {
            running = true;
            status = 'Gooble is roaming 👀';
          } else {
            status = 'Grant overlay permission then try again';
          }
        });
      }
    } catch (e) {
      setState(() => status = 'Error: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF08080F),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text('GOOBLE',
              style: TextStyle(
                color: Colors.white,
                fontSize: 36,
                fontWeight: FontWeight.bold,
                letterSpacing: 10,
              ),
            ),
            const SizedBox(height: 16),
            Text(status,
              style: const TextStyle(
                color: Colors.white38,
                fontSize: 13,
                letterSpacing: 2,
              ),
            ),
            const SizedBox(height: 50),
            GestureDetector(
              onTap: toggleGooble,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 44, vertical: 14),
                decoration: BoxDecoration(
                  border: Border.all(
                    color: running ? Colors.redAccent : Colors.white24),
                  borderRadius: BorderRadius.circular(30),
                ),
                child: Text(
                  running ? 'STOP GOOBLE' : 'LAUNCH GOOBLE',
                  style: TextStyle(
                    color: running ? Colors.redAccent : Colors.white70,
                    fontSize: 13,
                    letterSpacing: 3,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 20),
            const Text(
              'Tap & hold Gooble to talk\nGooble stays alive when app is closed',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.white24, fontSize: 11, letterSpacing: 1),
            ),
          ],
        ),
      ),
    );
  }
}
