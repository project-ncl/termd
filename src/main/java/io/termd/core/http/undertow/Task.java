package io.termd.core.http.undertow;

import io.termd.core.ProcessStatus;
import io.termd.core.Status;
import io.termd.core.io.BinaryDecoder;
import io.termd.core.readline.Readline;
import io.termd.core.tty.Signal;
import io.termd.core.tty.TtyConnection;
import io.termd.core.util.Handler;
import io.termd.core.util.Helper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
* @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
*/
class Task extends Thread {

  private UndertowProcessBootstrap undertowProcessBootstrap;
  final TtyConnection conn;
  final Readline readline;
  final String line;
  final ProcessStatus processStatus = new ProcessStatus(Status.NEW);

  public Task(UndertowProcessBootstrap undertowProcessBootstrap, TtyConnection conn, Readline readline, String line) {
    this.undertowProcessBootstrap = undertowProcessBootstrap;
    this.conn = conn;
    this.readline = readline;
    this.line = line;
  }

  private class Pipe extends Thread {

    private final Charset charset = StandardCharsets.UTF_8; // We suppose the process out/err uses UTF-8
    private final InputStream in;
    private final BinaryDecoder decoder = new BinaryDecoder(charset, new Handler<int[]>() {
      @Override
      public void handle(final int[] codepoints) {
        conn.schedule(new Runnable() {
          @Override
          public void run() {

            // Replace any \n by \r\n (need to improve that somehow...)
            int len = codepoints.length;
            for (int i = 0;i < codepoints.length;i++) {
              if (codepoints[i] == '\n' && (i == 0 || codepoints[i -1] != '\r')) {
                len++;
              }
            }
            int ptr = 0;
            int[] corrected = new int[len];
            for (int i = 0;i < codepoints.length;i++) {
              if (codepoints[i] == '\n' && (i == 0 || codepoints[i -1] != '\r')) {
                corrected[ptr++] = '\r';
                corrected[ptr++] = '\n';
              } else {
                corrected[ptr++] = codepoints[i];
              }
            }
            conn.writeHandler().handle(corrected);
          }
        });
      }
    });

    public Pipe(InputStream in) {
      this.in = in;
    }

    @Override
    public void run() {
      byte[] buffer = new byte[512];
      while (true) {
        try {
          int l = in.read(buffer);
          if (l == -1) {
            break;
          }
          decoder.write(buffer, 0, l);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  @Override
  public void run() {
    ProcessBuilder builder = new ProcessBuilder(line.split("\\s+"));
    try {
      final Process process = builder.start();
      processStatus.setStatus(Status.RUNNING);
      conn.setSignalHandler(new Handler<Signal>() {
        boolean interrupted; // Signal state
        @Override
        public void handle(Signal signal) {
          if (signal == Signal.INT) {
            if (!interrupted) {
              interrupted = true;
              process.destroy();
            }
          }
        }
      });
      Pipe stdout = new Pipe(process.getInputStream());
      Pipe stderr = new Pipe(process.getErrorStream());
      stdout.start();
      stderr.start();
      try {
        process.waitFor();
        int exitValue = process.exitValue();
        if (exitValue == 0) {
          processStatus.setStatus(Status.SUCCESSFULLY_COMPLETED);
        } else {
          processStatus.setStatus(Status.FAILED);
        }
      } catch (InterruptedException e) {
        processStatus.setStatus(Status.INTERRUPTED);
        Thread.currentThread().interrupt();
      }
      try {
        stdout.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      try {
        stderr.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    } catch (IOException e) {
      conn.writeHandler().handle(Helper.toCodePoints(e.getMessage() + "\r\n"));
    }

    // Read line again
    conn.setSignalHandler(null);
    conn.schedule(new Runnable() {
      @Override
      public void run() {
        undertowProcessBootstrap.read(conn, readline);
      }
    });
  }

  public ProcessStatus getProcessStatus() {
    return processStatus;
  }
}
