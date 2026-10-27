// The Samsung's own browser opens this page (Vestor's camera check). It lists the cameras and
// microphones the TV offers web pages, reports the counts to Vestor and says what they mean.
// Written for the 2020 Samsung browser (Chromium 69): no newer syntax, and only device kinds,
// never names, are read (names need camera permission, which a plain-HTTP page cannot get).
(function () {
  'use strict';
  var title = document.getElementById('title');
  var detail = document.getElementById('detail');
  var next = document.getElementById('next');
  var m = /Gecko\)\s+(?:Chrome\/)?(\d+)\./.exec(navigator.userAgent);
  var engine = m ? parseInt(m[1], 10) : 0;

  function plural(n, word) {
    return n + ' ' + word + (n === 1 ? '' : 's');
  }

  function report(r) {
    var x = new XMLHttpRequest();
    x.open('POST', '/api/camcheck');
    x.setRequestHeader('Content-Type', 'application/json');
    x.setRequestHeader('X-Vestor', '1');
    x.onload = function () {
      next.textContent = x.status === 200
        ? 'Vestor has the result. Press Home on the Fire TV remote to go back.'
        : 'Start the check from Vestor first (Video Calls, then Menu), then open this page again.';
    };
    x.onerror = function () {
      next.textContent = 'Could not reach Vestor. Press Home on the Fire TV remote to go back.';
    };
    x.send(JSON.stringify(r));
  }

  function done(api, cams, mics) {
    if (!api) {
      title.textContent = 'This TV cannot use a webcam';
      detail.textContent = 'Its browser has no way to list cameras.';
    } else if (cams === 0) {
      title.textContent = 'This TV does not see a webcam';
      detail.textContent = (mics === 0 ? 'It sees no camera and no microphone.' : 'It sees ' + plural(mics, 'microphone') + ' but no camera.')
        + ' If the webcam is plugged into the TV\'s USB port, this TV does not support webcams.';
    } else {
      title.textContent = 'This TV sees a webcam';
      detail.textContent = plural(cams, 'camera') + ' and ' + plural(mics, 'microphone') + '.';
    }
    report({ engine: engine, devices: api, cameras: cams, mics: mics });
  }

  if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) {
    done(false, 0, 0);
    return;
  }
  navigator.mediaDevices.enumerateDevices().then(function (list) {
    var cams = 0, mics = 0;
    for (var i = 0; i < list.length; i++) {
      if (list[i].kind === 'videoinput') cams++;
      else if (list[i].kind === 'audioinput') mics++;
    }
    done(true, cams, mics);
  }, function () {
    done(false, 0, 0);
  });
})();
