const admin = require('firebase-admin');
const config = require('./config.json');
const functions = require('firebase-functions');
const got = require('got');
const htmlToText = require('html-to-text');
const nodemailer = require('nodemailer');


admin.initializeApp();


const db = admin.firestore();
var smtpClient = null;


function findURLs (text)
{
    let urls = new Array();

    if (text) {
        for (const line of text.split("\r\n")) {
            url = line.trim();
            try {
                // Just see if it's a valid URL.
                new URL(url);

                // If there was no exception, add it to the list.
                urls.push(url);
            } catch (e) {
                null;
            }
        }
    }

    return urls;
}


function validateCloudMailin (req)
{
    let valid = false;

    if (config.cloudmailin.key) {
        valid = req.query.key === config.cloudmailin.key;
    } else {
        valid = true;
    }

    return valid;
}


async function handleCloudMailin (req, res)
{
    if (!validateCloudMailin(req)) {
        res.send(403).end();
        return null;
    }

    let email = req.body.envelope.from;

    console.log(`Received CloudMailin for ${email}`);

    if (email) {
        emailRef = db.collection("emails").doc(email)
        emailDoc = await emailRef.get()

        if (!emailDoc.exists) {
            email = null;
        }
    }

    if (email) {
        const batch = db.batch();

        for (const url of findURLs(req.body.plain)) {
            request = db.collection("requests").doc();
            batch.set(request, {url: url, email: email, received: new Date()});
        }

        await batch.commit();
    }

    res.status(200).end();

    return null;
}


async function urlToHtml (url)
{
    let html = null

    try {
        const response = await got(url, {
            throwHttpErrors: false
        });

        if (response.statusCode === 200) {
            html = response.body;
        }
    } catch (error) {
        console.log(error);
    }

    return html;
}


function htmlToTitle (html)
{
    let title = null;

    if (html) {
        match = html.match(/<title(?: .*)?>(.*?)<\/title>/i);
        if (match) {
            title = match[1];
        }
    }

    return title
}


async function sendEmail (msg) {
    if (!smtpClient) {
        smtpClient = nodemailer.createTransport({
            host: config.smtp.hostname,
            port: config.smtp.port,
            secure: false,
            requireTLS: true,
            auth: {
                user: config.smtp.username,
                pass: config.smtp.password
            },
            logger: false
        });
    }

    console.log(`Sending page to ${msg.to} via ${config.smtp.hostname}`);

    return smtpClient.sendMail(msg);
}


async function handleRequest (snapshot, context)
{
    const data = snapshot.data();
    if (data.url) {
        let response, error, updates = {success: false};
        let html, title, text;

        try {
            response = await got(data.url, {
                throwHttpErrors: true
            });
            html = response.body;

            if (html) {
                title = htmlToTitle(html);
                text = htmlToText.fromString(html);
            }

            if (text) {
                await sendEmail({
                    from: config.smtp.sender,
                    to: data.email,
                    subject: title || response.url,
                    text: `${response.url}\r\n\r\n\r\n${text}`
                })

                updates.success = true;
                updates.title = title;
            }
        } catch (e) {
            console.log(e);
            updates.error = e.message
        }

        if (!updates.success) {
            try {
                await sendEmail({
                    from: config.smtp.sender,
                    to: data.email,
                    bcc: "psagers@ignorare.net",
                    subject: "Oops...",
                    text: `Something went wrong processing your request. That's why we call it a prototype. We'll have a look.

Request ID: ${snapshot.id}
URL: ${data.url}

${updates.error}
`
                });
            } catch (e) {
                console.error(e);
            }
        }

        updates.completed = new Date();
        await snapshot.ref.update(updates);
    }

    return null;
}


exports.cloudMailin = functions.https
    .onRequest(handleCloudMailin);

exports.newRequest = functions.firestore
    .document('requests/{docId}')
    .onCreate(handleRequest);
